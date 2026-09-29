package net.bluegaria.titlescreen.client.video;

import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVCodecHWConfig;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVBufferRef;
import org.bytedeco.ffmpeg.avutil.AVChannelLayout;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avformat;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.ffmpeg.global.swresample;
import org.bytedeco.ffmpeg.global.swscale;
import org.bytedeco.ffmpeg.swresample.SwrContext;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.javacpp.PointerPointer;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * FFmpeg based {@link VideoPlayer} using the libraries bundled with the mod.
 *
 * <p>libavformat demuxes the container (WebM/Matroska), libavcodec decodes VP9 video and
 * Opus audio - with frame multithreading, which is what keeps 4K software decoding
 * playable - and libswscale converts every picture to RGBA, the format the texture layer
 * expects. The sound is resampled to 16-bit PCM by libswresample and handed to
 * {@link AudioOutput}, so the audio no longer runs through Minecraft's sound engine or any
 * system player; the mod is fully self-contained.</p>
 *
 * <p>Converting through swscale keeps whatever the source offers: an ordinary VP9 clip is
 * converted from YUV, and a clip with an alpha plane survives as one (libVLC discarded alpha in
 * its conversion chain - swscale simply carries it through).</p>
 *
 * <p>Threading model: open/preload happens on the caller's background thread, one decode thread
 * walks the file and feeds the {@link VideoFrameSink} and the audio queue, one audio thread
 * writes PCM (its blocking writes are the playback clock), and the client thread only ever
 * touches cheap volatile state - never a native call.</p>
 */
public final class FfmpegVideoPlayer implements VideoPlayer {

    /**
     * What this player does with the media.
     *
     * <p>A baked video (one file for the loading scene and the intro) is played by two players:
     * the picture by a silent video player, the sound by an audio-only one. That is deliberate -
     * separate players have separate decode threads. The video pipeline cannot always keep up
     * while the game is loading a resource pack, and in a combined pipeline the late video delays
     * the audio packets with it, which is heard as the sound cutting out. The picture may drop
     * frames; the sound will not.</p>
     */
    public enum Mode {
        /** Video through the frame sink, audio played through the audio output. */
        VIDEO,
        /** Video through the frame sink, no audio decoding/output at all. */
        SILENT_VIDEO,
        /** Audio only: no video decoder and no frame callbacks. */
        AUDIO
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("Titlescreen/Video");

    /** {@code EAGAIN} as FFmpeg reports it (the negated errno); EAGAIN is 11 on all three OSes. */

    private static final int AVERROR_EAGAIN = -11;
    /** Decoder threading modes (FFmpeg's FF_THREAD_FRAME/FF_THREAD_SLICE macros). */
    private static final int FF_THREAD_FRAME = 1;
    private static final int FF_THREAD_SLICE = 2;
    /**
     * How far the picture may lead the audible sound before the decoder waits. This is the whole
     * A/V sync of the combined player: frames are shown the moment they are decoded, so a small
     * allowance is all the picture should get (it needs one to decode ahead, not hundreds of
     * milliseconds - every millisecond of lead is a millisecond the sound lags behind).
     */
    private static final double MAX_VIDEO_LEAD_MS = 60.0;

    /**
     * How far ahead of the audible clock the demuxed audio has to be before the picture's pacing
     * may stall the demuxer. The picture is paced against the *audible* position, so without this
     * the demuxer never reads further ahead than the picture's lead and the audio output runs on
     * an empty buffer: it underruns at the first stutter and the sound cuts out. Measured as
     * stream time between the newest queued audio and the clock - the whole pipeline's depth,
     * wherever the buffering happens to sit.
     */
    private static final long AUDIO_CUSHION_MS = 400L;

    /**
     * How long the audio clock may sit still while unpaused before the picture stops waiting for
     * it: a blocked audio thread (an output wedged in its native call) must not freeze the video.
     */
    private static final long AUDIO_CLOCK_STALE_NANOS = 600_000_000L;

    public FfmpegVideoPlayer() {
        this(Mode.VIDEO);
    }

    private FfmpegVideoPlayer(Mode mode) {
        this.mode = mode;
    }

    /** A video player whose audio output is disabled - see {@link Mode#SILENT_VIDEO}. */
    public static FfmpegVideoPlayer silentVideo() {
        return new FfmpegVideoPlayer(Mode.SILENT_VIDEO);
    }

    /** An audio-only player, independent of any video player - see {@link Mode#AUDIO}. */
    public static FfmpegVideoPlayer audioOnly() {
        return new FfmpegVideoPlayer(Mode.AUDIO);
    }

    private boolean hasVideoOutput() {
        return this.mode != Mode.AUDIO;
    }

    private boolean hasAudioOutput() {
        return this.mode != Mode.SILENT_VIDEO;
    }

    private final Mode mode;
    private final VideoFrameSink sink = new VideoFrameSink();
    private Path file;

    private volatile boolean closed;
    private volatile boolean finished;
    private volatile boolean debug;

    // Written once by open(), read from any thread.
    private volatile long lengthMs;
    private volatile int sourceWidth;
    private volatile int sourceHeight;
    private volatile boolean firstFrameSeen;

    // Control values, written from the client thread.
    private volatile float volume = 1.0f;
    private volatile boolean paused;
    private volatile long seekRequestMs = -1L;
    private volatile long audioDelayMs;
    private volatile boolean pauseAfterFirstFrame;
    private volatile boolean preloaded;

    // Playback position, cached from the decode/audio threads for the client thread.
    private volatile long cachedTimeMs = -1L;
    private volatile long audioClockMs = -1L;
    /** Whether {@link #audioClockMs} reflects a real position (the sound's, or a seek target). */
    private volatile boolean audioClockValid;
    /** When {@link #audioClockMs} last advanced - drives the stale-audio fallback in {@link #clockMs}. */
    private volatile long audioClockStampNanos;

    // Audio clock bookkeeping (audio thread): the clock is the *audible* position - what the
    // device has played - read off the line's media time and mapped onto the stream's timestamps.
    private long audioAnchorUs = Long.MIN_VALUE;
    private double audioAnchorPtsMs;
    /** Bytes handed to the line since the clock anchor; bounds how far the media position may be. */
    private long audioWrittenBytes;
    private long audioAnchorWrittenBytes;
    /** The media position never runs backwards; a reported regression is held instead of applied. */
    private long audioMediaUsFloor = Long.MIN_VALUE;
    /** Bumped on every seek; a chunk written across a seek must not update the clock. */
    private volatile int audioSeekGeneration;
    /** After a seek: decoded sound before this stream position is dropped (the demuxer restarts at the nearest keyframe, which can sit before the target). */
    private volatile long skipAudioUntilMs = -1L;
    /** The newest queued audio's stream position, and when it was queued - the cushion measure. */
    private volatile long lastAudioQueuedPtsMs = Long.MIN_VALUE;
    private volatile long lastAudioQueueNanos;
    /** Output mixer name for the sound, or empty for automatic selection (see {@link AudioOutput}). */
    private volatile String audioDevice = "";

    private Thread decodeThread;
    private Thread audioThread;
    private final BlockingQueue<PcmChunk> audioChunks = new ArrayBlockingQueue<>(64);

    // FFmpeg objects: created in open(), used only by the decode thread, freed in close().
    private AVFormatContext formatContext;
    private AVCodecContext videoCodecContext;
    private AVCodecContext audioCodecContext;
    private AVPacket packet;
    private AVFrame frame;
    private int videoStreamIndex = -1;
    private int audioStreamIndex = -1;
    private double videoTimeBaseMs = 1.0;
    private double audioTimeBaseMs = 1.0;
    private double frameDurationMs = 33.3;

    private SwsContext swsContext;
    private int swsFormat = -1;
    private int swsWidth = -1;
    private int swsHeight = -1;
    private AVFrame rgbaFrame;

    private SwrContext swrContext;
    private int swrInFormat = -1;
    private int swrInRate = -1;
    private int swrInChannels = -1;
    private ByteBuffer pcmBuffer;
    private PointerPointer pcmPlanes;

    private AudioOutput audioOutput;
    private int audioOutRate;
    private int audioOutChannels;

    // Hardware decoding: a device is created per session (D3D11VA on Windows, VideoToolbox on
    // macOS, VAAPI or NVDEC on Linux); hardware frames are downloaded to system memory and then
    // travel through the same swscale conversion as software frames.
    private volatile boolean hardwareDecoding = true;
    private static final AtomicBoolean HW_UNUSABLE = new AtomicBoolean(false);
    private AVBufferRef hwDeviceBuf;
    private int hwPixFmt = -1;
    private boolean hwActive;
    private int hwTransferFailures;
    private long presentedFrames;
    private AVFrame swFrame;

    // Decode-thread bookkeeping.
    private long anchorNanos;
    private long pausedAtNanos;
    private long clockOffsetMs;
    /** Whether {@link #audioClockMs} paces the picture; untrustworthy outputs run on wall time. */
    private volatile boolean useAudioClock;
    /** The playback clock never steps backwards between seeks (see {@link #clockMs}). */
    private long clockFloorMs = Long.MIN_VALUE;
    private long lastVideoPtsMs = -1L;
    private long skipFramesUntilMs = -1L;
    private boolean singleFrameAfterSeek;
    private boolean pendingStartSilence;
    private boolean audioQueueWarningLogged;
    private long lastAvSyncLogNanos;

    /** One resampled chunk of PCM on its way to the audio output. */
    private record PcmChunk(byte[] data, long ptsMs, long durationMs) {
    }

    // ------------------------------------------------------------------
    // Opening
    // ------------------------------------------------------------------

    @Override
    public boolean start(Path file, int volumePercent) {
        return open(file, volumePercent);
    }

    @Override
    public boolean preload(Path file, int volumePercent) {
        this.pauseAfterFirstFrame = true;
        boolean ok = open(file, volumePercent);
        this.preloaded = ok;
        if (!ok) {
            this.pauseAfterFirstFrame = false;
        }
        return ok;
    }

    private boolean open(Path file, int volumePercent) {
        if (!FfmpegNativeLibrary.prepare()) {
            LOGGER.warn("The bundled FFmpeg libraries are not available - {} cannot be played", file);
            return false;
        }
        this.file = file;
        this.volume = Math.max(0.0f, Math.min(1.0f, volumePercent / 100.0f));

        AVFormatContext context = new AVFormatContext(null);
        int ret = avformat.avformat_open_input(context, file.toAbsolutePath().toString(), null, null);
        if (ret < 0) {
            LOGGER.warn("Could not open {}: {}", file, errorString(ret));
            return false;
        }
        ret = avformat.avformat_find_stream_info(context, (PointerPointer) null);
        if (ret < 0) {
            LOGGER.warn("Could not read the streams of {}: {}", file, errorString(ret));
            avformat.avformat_close_input(context);
            return false;
        }
        if (this.closed) {
            avformat.avformat_close_input(context);
            return false;
        }
        this.formatContext = context;

        // AV_TIME_BASE is microseconds.
        this.lengthMs = context.duration() > 0 ? context.duration() / 1000L : 0L;
        this.videoStreamIndex = findStream(context, avutil.AVMEDIA_TYPE_VIDEO);
        this.audioStreamIndex = findStream(context, avutil.AVMEDIA_TYPE_AUDIO);

        if (hasVideoOutput() && this.videoStreamIndex >= 0) {
            AVStream stream = context.streams(this.videoStreamIndex);
            this.sourceWidth = stream.codecpar().width();
            this.sourceHeight = stream.codecpar().height();
            this.videoTimeBaseMs = timeBaseMs(stream.time_base());
            AVRational frameRate = stream.avg_frame_rate();
            if (frameRate.num() > 0 && frameRate.den() > 0) {
                this.frameDurationMs = 1000.0 * frameRate.den() / frameRate.num();
            }
            this.videoCodecContext = openDecoder(context, this.videoStreamIndex, "video");
            if (this.videoCodecContext == null) {
                LOGGER.warn("{} has no decodable video track", file);
            }
        }

        int audioRate = 48000;
        int audioChannels = 2;
        if (hasAudioOutput() && this.audioStreamIndex >= 0) {
            AVStream stream = context.streams(this.audioStreamIndex);
            this.audioTimeBaseMs = timeBaseMs(stream.time_base());
            audioRate = stream.codecpar().sample_rate();
            audioChannels = Math.max(1, stream.codecpar().ch_layout().nb_channels());
            this.audioCodecContext = openDecoder(context, this.audioStreamIndex, "audio");
        }
        if (this.audioCodecContext != null) {
            // The line is opened here, before playback: an output that already exists only has to
            // be fed, which keeps the sound a hair behind the picture at worst instead of seconds
            // (the audio preload runs silently at volume 0 until it is turned up).
            this.audioOutput = AudioOutput.open(file, audioRate, audioChannels, this.volume, this.debug,
                    this.audioDevice);
            if (this.audioOutput != null) {
                this.audioOutRate = this.audioOutput.rate();
                this.audioOutChannels = this.audioOutput.channels();
                this.useAudioClock = this.audioOutput.positionTrustworthy();
            }
        }

        this.packet = avcodec.av_packet_alloc();
        this.frame = avutil.av_frame_alloc();
        this.swFrame = avutil.av_frame_alloc();
        this.pendingStartSilence = this.audioDelayMs < 0;
        this.anchorNanos = System.nanoTime();
        this.clockOffsetMs = 0L;
        this.clockFloorMs = Long.MIN_VALUE;
        if (this.debug && this.videoCodecContext != null) {
            LOGGER.info("{}: {}x{} video, {} Hz x {} channel audio, {} ms",
                    file, this.sourceWidth, this.sourceHeight, audioRate, audioChannels, this.lengthMs);
        }

        this.decodeThread = new Thread(this::decodeLoop, "Titlescreen-FFmpeg-Decode");
        this.decodeThread.setDaemon(true);
        // Best effort, but it matters on the heavy first launches: the picture may drop frames by
        // design, the sound must not be starved by the video pipeline when the machine is loaded.
        try {
            this.decodeThread.setPriority(Thread.NORM_PRIORITY - 1);
        } catch (Throwable ignored) {
            // Priority adjustments are a hint, never a requirement.
        }
        this.decodeThread.start();
        if (this.audioOutput != null) {
            this.audioThread = new Thread(this::audioLoop, "Titlescreen-FFmpeg-Audio");
            this.audioThread.setDaemon(true);
            try {
                this.audioThread.setPriority(Thread.NORM_PRIORITY + 1);
            } catch (Throwable ignored) {
                // Priority adjustments are a hint, never a requirement.
            }
            this.audioThread.start();
        }
        return !this.closed;
    }

    /** Index of the first stream of the given media type, ignoring attached cover art. */
    private static int findStream(AVFormatContext context, int type) {
        for (int i = 0; i < context.nb_streams(); i++) {
            AVStream stream = context.streams(i);
            if (stream.codecpar().codec_type() == type
                    && (stream.disposition() & avformat.AV_DISPOSITION_ATTACHED_PIC) == 0) {
                return i;
            }
        }
        return -1;
    }

    private AVCodecContext openDecoder(AVFormatContext context, int index, String label) {
        AVStream stream = context.streams(index);
        AVCodec codec = avcodec.avcodec_find_decoder(stream.codecpar().codec_id());
        if (codec == null) {
            LOGGER.warn("No {} decoder for the codec of stream {} in {}", label, index, this.file);
            return null;
        }
        AVCodecContext codecContext = avcodec.avcodec_alloc_context3(codec);
        int ret = avcodec.avcodec_parameters_to_context(codecContext, stream.codecpar());
        if (ret < 0) {
            LOGGER.warn("Could not copy the {} parameters of stream {} in {}: {}",
                    label, index, this.file, errorString(ret));
            avcodec.avcodec_free_context(codecContext);
            return null;
        }
        // Decode with every core: FFmpeg's VP9 decoder scales with frame threads, which is what
        // keeps 4K software decoding playable at all.
        codecContext.thread_count(0);
        codecContext.thread_type(FF_THREAD_FRAME | FF_THREAD_SLICE);
        if (label.equals("video")) {
            setupHardwareDecoding(codec, codecContext);
        }
        ret = avcodec.avcodec_open2(codecContext, codec, (AVDictionary) null);
        if (ret < 0) {
            LOGGER.warn("Could not open the {} decoder of stream {} in {}: {}",
                    label, index, this.file, errorString(ret));
            avcodec.avcodec_free_context(codecContext);
            return null;
        }
        return codecContext;
    }

    private static double timeBaseMs(AVRational timeBase) {
        return timeBase.num() > 0 && timeBase.den() > 0
                ? 1000.0 * timeBase.num() / timeBase.den() : 1.0;
    }

    // ------------------------------------------------------------------
    // Hardware decoding (VP9 via D3D11VA / VideoToolbox / VAAPI / NVDEC)
    // ------------------------------------------------------------------

    /** The platform's hardware device type, in the order it should be tried. */
    private static int[] hwDeviceTypes() {
        String platform = Loader.getPlatform();
        if (platform.startsWith("windows")) {
            return new int[]{avutil.AV_HWDEVICE_TYPE_D3D11VA};
        }
        if (platform.startsWith("macosx")) {
            return new int[]{avutil.AV_HWDEVICE_TYPE_VIDEOTOOLBOX};
        }
        if (platform.startsWith("linux")) {
            // VAAPI covers Intel/AMD; NVDEC covers NVIDIA. Both are tried in turn.
            return new int[]{avutil.AV_HWDEVICE_TYPE_VAAPI, avutil.AV_HWDEVICE_TYPE_CUDA};
        }
        return new int[0];
    }

    private static String hwDeviceTypeName(int type) {
        if (type == avutil.AV_HWDEVICE_TYPE_D3D11VA) return "D3D11VA";
        if (type == avutil.AV_HWDEVICE_TYPE_VIDEOTOOLBOX) return "VideoToolbox";
        if (type == avutil.AV_HWDEVICE_TYPE_VAAPI) return "VAAPI";
        if (type == avutil.AV_HWDEVICE_TYPE_CUDA) return "NVDEC/CUDA";
        return "type " + type;
    }

    /**
     * Requests hardware-decoded frames for the video decoder when the option is on, the platform
     * offers a device type, the codec supports it, and a previous session has not proven the
     * hardware path broken in this process. Anything else quietly keeps software decoding, which
     * is always available as the fallback.
     */
    private void setupHardwareDecoding(AVCodec codec, AVCodecContext codecContext) {
        if (!this.hardwareDecoding || HW_UNUSABLE.get()) {
            return;
        }
        for (int type : hwDeviceTypes()) {
            // Does the codec have a hardware configuration for this device type at all?
            boolean codecSupports = false;
            for (int i = 0; ; i++) {
                AVCodecHWConfig config = avcodec.avcodec_get_hw_config(codec, i);
                if (config == null) {
                    break;
                }
                if ((config.methods() & avcodec.AV_CODEC_HW_CONFIG_METHOD_HW_DEVICE_CTX) != 0
                        && config.device_type() == type) {
                    this.hwPixFmt = config.pix_fmt();
                    codecSupports = true;
                    break;
                }
            }
            if (!codecSupports) {
                continue;
            }
            if (!createHwDevice(type)) {
                continue;
            }
            codecContext.hw_device_ctx(avutil.av_buffer_ref(this.hwDeviceBuf));
            this.hwActive = true;
            if (this.debug) {
                LOGGER.info("Decoding {} on the GPU ({})", this.file, hwDeviceTypeName(type));
            }
            return;
        }
        if (this.debug) {
            LOGGER.info("No usable hardware decoder for {} - decoding in software", this.file);
        }
    }

    private boolean createHwDevice(int type) {
        this.hwDeviceBuf = new AVBufferRef(null);
        // The default device first; on Linux also try every render node, since multi-GPU
        // machines may expose the working VAAPI device on the second one.
        if (tryCreateHwDevice(type, null)) {
            return true;
        }
        if (type == avutil.AV_HWDEVICE_TYPE_VAAPI) {
            File[] nodes = new File("/dev/dri").listFiles((dir, name) -> name.startsWith("renderD"));
            if (nodes != null) {
                Arrays.sort(nodes);
                for (File node : nodes) {
                    if (tryCreateHwDevice(type, node.getAbsolutePath())) {
                        return true;
                    }
                }
            }
        }
        this.hwDeviceBuf = null;
        return false;
    }

    private boolean tryCreateHwDevice(int type, String device) {
        int ret = avutil.av_hwdevice_ctx_create(this.hwDeviceBuf, type, device, null, 0);
        if (ret < 0) {
            if (this.debug) {
                LOGGER.info("Could not open a {} device{}: {}", hwDeviceTypeName(type),
                        device == null ? "" : " (" + device + ")", errorString(ret));
            }
            return false;
        }
        return true;
    }

    /**
     * Downloads a hardware-decoded frame into system memory (NV12) so the usual swscale
     * conversion can run on it.
     */
    private boolean downloadHwFrame(AVFrame hwFrame) {
        this.swFrame.format(avutil.AV_PIX_FMT_NV12);
        this.swFrame.width(hwFrame.width());
        this.swFrame.height(hwFrame.height());
        if (avutil.av_hwframe_transfer_data(this.swFrame, hwFrame, 0) < 0) {
            avutil.av_frame_unref(this.swFrame);
            this.hwTransferFailures++;
            if (this.hwTransferFailures >= 3) {
                // The hardware path is not delivering usable frames - remember that for the rest
                // of the session; the manager's restart then comes back in software.
                HW_UNUSABLE.set(true);
                LOGGER.warn("Hardware frame download kept failing - falling back to software decoding");
            }
            return false;
        }
        // Set after the transfer: it allocates the destination buffers and resets the fields.
        this.swFrame.pts(hwFrame.pts());
        this.hwTransferFailures = 0;
        return true;
    }

    // ------------------------------------------------------------------
    // Decoding (decode thread)
    // ------------------------------------------------------------------

    private void decodeLoop() {
        try {
            while (!this.closed) {
                long seekTo = this.seekRequestMs;
                if (seekTo >= 0L) {
                    this.seekRequestMs = -1L;
                    performSeek(seekTo);
                    continue;
                }
                if (this.paused) {
                    if (!this.singleFrameAfterSeek) {
                        sleepUnchecked(10);
                        continue;
                    }
                    // A seek while paused still has to put a picture on screen.
                    this.singleFrameAfterSeek = false;
                }
                if (this.lastVideoPtsMs >= 0L
                        && this.lastVideoPtsMs + this.frameDurationMs - clockMs() > MAX_VIDEO_LEAD_MS
                        && audioCushionSufficient()) {
                    // Paced: stay a few frames ahead of the clock at most, so the picture can
                    // never run away from the sound - but only once the sound has its cushion
                    // too, otherwise the demuxer would never read further than the picture's
                    // lead and the audio output would starve behind it.
                    sleepUnchecked(5);
                    continue;
                }
                int read = avformat.av_read_frame(this.formatContext, this.packet);
                if (read < 0) {
                    if (read != avutil.AVERROR_EOF) {
                        LOGGER.warn("Reading {} stopped: {}", this.file, errorString(read));
                    }
                    drainDecoders();
                    this.finished = true;
                    sleepUnchecked(20); // park at the end until a seek restarts playback
                    continue;
                }
                int index = this.packet.stream_index();
                if (index == this.videoStreamIndex) {
                    decodeVideoPacket();
                } else if (index == this.audioStreamIndex && this.audioCodecContext != null) {
                    decodeAudioPacket();
                }
                avcodec.av_packet_unref(this.packet);
            }
        } catch (Throwable t) {
            LOGGER.warn("Decoding crashed for {}", this.file, t);
            this.finished = true;
        }
    }

    /**
     * Whether the audio pipeline holds enough stream time to ride out a decode hiccup: the newest
     * queued audio has to lead the audible clock by {@link #AUDIO_CUSHION_MS}. Players without
     * audio always count as cushioned, and so does a track that stopped delivering packets (it
     * ended - nothing left to protect, the picture must not be dropped for its sake).
     */
    private boolean audioCushionSufficient() {
        if (this.audioOutput == null || this.audioCodecContext == null) {
            return true;
        }
        if (this.lastAudioQueueNanos == 0L
                || System.nanoTime() - this.lastAudioQueueNanos > 2_000_000_000L) {
            return true;
        }
        return this.lastAudioQueuedPtsMs - clockMs() >= AUDIO_CUSHION_MS;
    }

    private void decodeVideoPacket() {
        int ret = avcodec.avcodec_send_packet(this.videoCodecContext, this.packet);
        if (ret < 0 && ret != AVERROR_EAGAIN) {
            LOGGER.warn("The video decoder rejected a packet of {}: {}", this.file, errorString(ret));
            return;
        }
        receiveVideoFrames();
    }

    private void receiveVideoFrames() {
        while (!this.closed) {
            int ret = avcodec.avcodec_receive_frame(this.videoCodecContext, this.frame);
            if (ret < 0) {
                // EAGAIN: the decoder wants more input; a negative value at the end: drained.
                return;
            }
            AVFrame source = this.frame;
            if (this.hwActive && this.frame.format() == this.hwPixFmt) {
                if (!downloadHwFrame(this.frame)) {
                    continue;
                }
                source = this.swFrame;
            }
            long pts = source.pts();
            double ptsMs;
            if (pts == avutil.AV_NOPTS_VALUE) {
                // Some hardware paths lose the timestamp: synthesize one from the frame rate -
                // that is what the pacing and the end-of-video checks need it for.
                ptsMs = this.lastVideoPtsMs >= 0L ? this.lastVideoPtsMs + this.frameDurationMs
                        : clockMs();
            } else {
                ptsMs = pts * this.videoTimeBaseMs;
            }
            if (ptsMs < this.skipFramesUntilMs) {
                // Stale frame from before a seek.
                if (source != this.frame) {
                    avutil.av_frame_unref(this.swFrame);
                }
                continue;
            }
            if (ptsMs - clockMs() > MAX_VIDEO_LEAD_MS && !audioCushionSufficient()) {
                // Far ahead of the clock only because the sound is being fed its cushion: decode
                // (VP9 needs every frame as a reference) but skip the conversion and the
                // presentation. The picture drops these frames the same way it drops late frames
                // behind a slow decoder - a fraction of a second of skipped video instead of a
                // sound that cuts out.
                if (source != this.frame) {
                    avutil.av_frame_unref(this.swFrame);
                }
                this.lastVideoPtsMs = (long) ptsMs;
                continue;
            }
            presentFrame(source, ptsMs);
            if (source != this.frame) {
                avutil.av_frame_unref(this.swFrame);
            }
            this.lastVideoPtsMs = (long) ptsMs;
            this.cachedTimeMs = (long) ptsMs;
        }
    }

    private void presentFrame(AVFrame src, double ptsMs) {
        int format = src.format();
        int width = src.width();
        int height = src.height();
        if (width <= 0 || height <= 0) {
            return;
        }
        if (format != this.swsFormat || width != this.swsWidth || height != this.swsHeight) {
            recreateScaler(format, width, height);
            if (this.swsContext == null) {
                return;
            }
        }
        // The conversion writes straight into the sink's staging buffer: no intermediate frame
        // copy on the producer side, which is what keeps the decode thread comfortably inside the
        // frame budget at 4K.
        ByteBuffer target = this.sink.beginFrame(width, height);
        if (target == null) {
            return;
        }
        target.clear();
        avutil.av_image_fill_arrays(this.rgbaFrame.data(), this.rgbaFrame.linesize(),
                new BytePointer(target), avutil.AV_PIX_FMT_RGBA, width, height, 1);
        swscale.sws_scale(this.swsContext, src.data(), src.linesize(),
                0, height, this.rgbaFrame.data(), this.rgbaFrame.linesize());
        this.sink.processAlpha(target, width * height);
        this.sink.commitFrame(width, height);
        this.firstFrameSeen = true;
        this.presentedFrames++;
    }

    /** Builds (or rebuilds) the YUV(A)/NV12 -> RGBA conversion for a new frame size/format. */
    private void recreateScaler(int format, int width, int height) {
        releaseScaler();
        this.swsContext = swscale.sws_getContext(width, height, format, width, height,
                avutil.AV_PIX_FMT_RGBA, swscale.SWS_BILINEAR, null, null, (DoublePointer) null);
        if (this.swsContext == null) {
            LOGGER.warn("Could not create a scaler for {} (format {})", this.file, format);
            return;
        }
        this.swsFormat = format;
        this.swsWidth = width;
        this.swsHeight = height;
        // The destination frame is a pointer carrier: av_image_fill_arrays re-points its data at
        // the sink's staging buffer for every frame.
        this.rgbaFrame = avutil.av_frame_alloc();
        if (this.debug) {
            LOGGER.info("Scaling {}x{} (format {}) to RGBA for {}", width, height, format, this.file);
        }
    }

    private void releaseScaler() {
        if (this.swsContext != null) {
            swscale.sws_freeContext(this.swsContext);
            this.swsContext = null;
        }
        if (this.rgbaFrame != null) {
            avutil.av_frame_free(this.rgbaFrame);
            this.rgbaFrame = null;
        }
        this.swsFormat = -1;
        this.swsWidth = -1;
        this.swsHeight = -1;
    }

    /** Pushes the decoders' remaining frames out at the end of the file. */
    private void drainDecoders() {
        if (this.videoCodecContext != null) {
            avcodec.avcodec_send_packet(this.videoCodecContext, (AVPacket) null);
            receiveVideoFrames();
        }
        if (this.audioCodecContext != null) {
            avcodec.avcodec_send_packet(this.audioCodecContext, (AVPacket) null);
            while (!this.closed) {
                int ret = avcodec.avcodec_receive_frame(this.audioCodecContext, this.frame);
                if (ret < 0) {
                    return;
                }
                queueAudioFrame();
            }
        }
    }

    // ------------------------------------------------------------------
    // Audio (decode thread + audio thread)
    // ------------------------------------------------------------------

    private void decodeAudioPacket() {
        int ret = avcodec.avcodec_send_packet(this.audioCodecContext, this.packet);
        if (ret < 0 && ret != AVERROR_EAGAIN) {
            LOGGER.warn("The audio decoder rejected a packet of {}: {}", this.file, errorString(ret));
            return;
        }
        while (!this.closed) {
            ret = avcodec.avcodec_receive_frame(this.audioCodecContext, this.frame);
            if (ret < 0) {
                return;
            }
            queueAudioFrame();
        }
    }

    /** Converts one decoded audio frame to 16-bit PCM and queues it for the audio thread. */
    private void queueAudioFrame() {
        int samples = this.frame.nb_samples();
        if (samples <= 0 || this.audioOutput == null) {
            return;
        }
        int inFormat = this.frame.format();
        int inRate = this.frame.sample_rate();
        int inChannels = Math.max(1, this.frame.ch_layout().nb_channels());
        if (inFormat != this.swrInFormat || inRate != this.swrInRate
                || inChannels != this.swrInChannels || this.swrContext == null) {
            recreateResampler(inFormat, inRate, inChannels);
            if (this.swrContext == null) {
                return;
            }
        }
        int outSamples = swresample.swr_get_out_samples(this.swrContext, samples);
        if (outSamples <= 0) {
            outSamples = samples;
        }
        int neededBytes = outSamples * this.audioOutChannels * 2;
        if (this.pcmBuffer == null || this.pcmBuffer.capacity() < neededBytes) {
            if (this.pcmBuffer != null) {
                MemoryUtil.memFree(this.pcmBuffer);
            }
            this.pcmBuffer = MemoryUtil.memAlloc(Math.max(neededBytes, 8192));
            // One-slot uint8_t* array pointing at the buffer. Built through the explicit array
            // form on purpose: the single-pointer PointerPointer constructor would wrap the
            // buffer's bytes as a pointer array instead of a pointer to it.
            this.pcmPlanes = new PointerPointer(new BytePointer[]{new BytePointer(this.pcmBuffer)});
        }
        int converted = swresample.swr_convert(this.swrContext, this.pcmPlanes, outSamples,
                this.frame.data(), samples);
        if (converted <= 0) {
            return;
        }
        int bytes = converted * this.audioOutChannels * 2;
        byte[] chunk = new byte[bytes];
        this.pcmBuffer.position(0).limit(bytes).get(chunk).clear();

        long pts = this.frame.pts();
        long ptsMs = pts == avutil.AV_NOPTS_VALUE ? -1L : Math.round(pts * this.audioTimeBaseMs);
        if (ptsMs >= 0 && ptsMs < this.skipAudioUntilMs) {
            // Stale samples from before a seek: the demuxer restarts at the nearest keyframe, which
            // can sit well before the seek target. Playing them would replay a snippet of the clip
            // (audible after every seek, and it made resync seeks never converge).
            return;
        }
        long durationMs = Math.round(converted * 1000.0 / this.audioOutRate);

        // The configured audio delay only ever lines a separate audio-only player up with another
        // player's picture: positive drops the head of the sound (plays later), negative prepends
        // silence once (plays earlier).
        if (this.mode == Mode.AUDIO && this.audioDelayMs > 0L && ptsMs >= 0
                && ptsMs < this.audioDelayMs) {
            int skipBytes = (int) Math.min(bytes,
                    (this.audioDelayMs - ptsMs) * bytesPerMs());
            if (skipBytes >= bytes) {
                return;
            }
            byte[] trimmed = new byte[bytes - skipBytes];
            System.arraycopy(chunk, skipBytes, trimmed, 0, trimmed.length);
            chunk = trimmed;
            durationMs -= Math.round(skipBytes / bytesPerMs());
            ptsMs = this.audioDelayMs;
        } else if (this.mode == Mode.AUDIO && this.audioDelayMs < 0L
                && this.pendingStartSilence && ptsMs <= 100L) {
            byte[] silence = new byte[(int) (-this.audioDelayMs * bytesPerMs())];
            enqueue(new PcmChunk(silence, Math.max(0L, ptsMs), -this.audioDelayMs));
            this.pendingStartSilence = false;
        }
        enqueue(new PcmChunk(chunk, ptsMs, durationMs));
        if (ptsMs >= 0L) {
            this.lastAudioQueuedPtsMs = ptsMs;
        }
        this.lastAudioQueueNanos = System.nanoTime();
        this.firstFrameSeen = true;
    }

    private double bytesPerMs() {
        return this.audioOutRate * this.audioOutChannels * 2 / 1000.0;
    }

    private void recreateResampler(int inFormat, int inRate, int inChannels) {
        if (this.swrContext != null) {
            swresample.swr_free(this.swrContext);
            this.swrContext = null;
        }
        this.swrInFormat = -1;
        AVChannelLayout outLayout = new AVChannelLayout();
        avutil.av_channel_layout_default(outLayout, this.audioOutChannels);
        this.swrContext = swresample.swr_alloc();
        int ret = swresample.swr_alloc_set_opts2(this.swrContext, outLayout,
                avutil.AV_SAMPLE_FMT_S16, this.audioOutRate,
                this.frame.ch_layout(), inFormat, inRate, 0, null);
        if (ret < 0 || swresample.swr_init(this.swrContext) < 0) {
            swresample.swr_free(this.swrContext);
            this.swrContext = null;
            LOGGER.warn("Could not resample {} Hz x {} channel audio (format {}) for {}",
                    inRate, inChannels, inFormat, this.file);
            return;
        }
        this.swrInFormat = inFormat;
        this.swrInRate = inRate;
        this.swrInChannels = inChannels;
        if (this.debug) {
            LOGGER.info("Resampling {} Hz x {} channel audio (format {}) to {} Hz x {} channels "
                    + "for {}", inRate, inChannels, inFormat, this.audioOutRate,
                    this.audioOutChannels, this.file);
        }
    }

    private void enqueue(PcmChunk chunk) {
        try {
            if (this.mode == Mode.AUDIO) {
                // Audio-only: the blocking queue is the player's pacing.
                this.audioChunks.put(chunk);
            } else if (!this.audioChunks.offer(chunk, 50, TimeUnit.MILLISECONDS)
                    && !this.audioQueueWarningLogged) {
                this.audioQueueWarningLogged = true;
                LOGGER.warn("The audio queue of {} stayed full - dropping sound chunks", this.file);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Writes queued PCM to the output. The blocking writes pace the sound feed; whichever clock
     * the picture runs on (see {@link #clockMs}), a fed output stays as close to it as it can.
     */
    private void audioLoop() {
        try {
            while (!this.closed) {
                PcmChunk chunk;
                try {
                    chunk = this.audioChunks.poll(20, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    continue;
                }
                if (chunk == null) {
                    continue;
                }
                while (this.paused && !this.closed) {
                    sleepUnchecked(5);
                }
                if (this.closed) {
                    return;
                }
                int generation = this.audioSeekGeneration;
                byte[] data = chunk.data();
                this.audioOutput.write(data);
                if (generation != this.audioSeekGeneration) {
                    // The chunk was in flight across a seek: its samples were flushed, and its
                    // position says nothing about the new playback position - re-anchor instead.
                    this.audioAnchorUs = Long.MIN_VALUE;
                    continue;
                }
                this.audioWrittenBytes += data.length;
                if (chunk.ptsMs() >= 0) {
                    updateAudioClock(chunk.ptsMs());
                }
                debugAvSync();
            }
        } catch (Throwable t) {
            // Never let the thread die silently: an audio-only player's decoder would block on its
            // full queue forever, and nobody would know why the sound stopped.
            LOGGER.warn("The audio output of {} failed - the video continues without sound", this.file, t);
        }
    }

    /**
     * Maps the output's media position onto the stream's timestamps. Only a position the output
     * itself accounts for ({@link AudioOutput#positionTrustworthy()}) paces the picture; the rest
     * use this as a diagnostic and as the audio-only player's reported position.
     */
    private void updateAudioClock(long chunkPtsMs) {
        // The clock is what is audible right now: the output's media time (what the device has
        // played) mapped onto the stream's timestamps at the anchor chunk.
        long mediaUs = this.audioOutput.mediaPositionUs();
        if (this.audioAnchorUs == Long.MIN_VALUE) {
            this.audioAnchorUs = mediaUs;
            this.audioAnchorPtsMs = chunkPtsMs;
            this.audioAnchorWrittenBytes = this.audioWrittenBytes;
            this.audioMediaUsFloor = mediaUs;
        }
        // The device's position is derived from buffer availability and can be briefly wrong
        // around underruns and sound-server round trips. Real playback never runs backwards and
        // can never have played more than was written since the anchor: clamping both ways keeps
        // a bogus report from leaping the clock.
        long writtenUs = this.audioAnchorUs
                + (this.audioWrittenBytes - this.audioAnchorWrittenBytes) * 500_000L
                    / Math.max(1, this.audioOutRate * Math.max(1, this.audioOutChannels));
        if (mediaUs > writtenUs) {
            mediaUs = writtenUs;
        }
        if (mediaUs < this.audioMediaUsFloor) {
            mediaUs = this.audioMediaUsFloor;
        }
        this.audioMediaUsFloor = mediaUs;
        this.audioClockMs = Math.round(this.audioAnchorPtsMs + (mediaUs - this.audioAnchorUs) / 1000.0);
        this.audioClockValid = true;
        this.audioClockStampNanos = System.nanoTime();
        if (this.mode == Mode.AUDIO) {
            // The audio-only player has no decode-side position: its cached time is the clock.
            // (A video player's cached time stays the decode thread's picture position.)
            this.cachedTimeMs = this.audioClockMs;
        }
    }

    /** One line per audio thread every ~5 s: the picture position against the audible sound. */
    private void debugAvSync() {
        if (!this.debug || this.mode == Mode.AUDIO) {
            return;
        }
        long now = System.nanoTime();
        if (now - this.lastAvSyncLogNanos < 5_000_000_000L) {
            return;
        }
        this.lastAvSyncLogNanos = now;
        if (this.audioClockValid && this.lastVideoPtsMs >= 0L) {
            LOGGER.info("A/V sync: picture {} ms, audible sound {} ms (picture leads by {} ms)",
                    this.lastVideoPtsMs, this.audioClockMs, this.lastVideoPtsMs - this.audioClockMs);
        }
    }

    // ------------------------------------------------------------------
    // Seeking and the playback clock (decode thread)
    // ------------------------------------------------------------------

    private void performSeek(long targetMs) {
        long targetUs = targetMs * 1000L;
        int ret = avformat.avformat_seek_file(this.formatContext, -1,
                Long.MIN_VALUE, targetUs, targetUs + avutil.AV_TIME_BASE, 0);
        if (ret < 0 && this.debug) {
            LOGGER.warn("Seeking {} to {} ms failed: {}", this.file, targetMs, errorString(ret));
        }
        if (this.videoCodecContext != null) {
            avcodec.avcodec_flush_buffers(this.videoCodecContext);
        }
        if (this.audioCodecContext != null) {
            avcodec.avcodec_flush_buffers(this.audioCodecContext);
        }
        this.audioChunks.clear();
        if (this.audioOutput != null) {
            this.audioOutput.flush();
        }
        // Chunks written across this seek belong to the old position: their writes must not
        // update the clock, and the clock re-anchors on the first post-seek chunk.
        this.audioSeekGeneration++;
        this.audioAnchorUs = Long.MIN_VALUE;
        this.audioClockMs = targetMs;
        this.audioClockValid = true; // approximate at the seek target until the sound is audible again
        this.audioClockStampNanos = System.nanoTime(); // grace period for the sound to re-prime
        this.finished = false;
        this.lastVideoPtsMs = -1L;
        this.skipFramesUntilMs = targetMs;
        // The demuxer restarts at the nearest keyframe, which can sit before the target: decoded
        // sound older than the target is dropped (see queueAudioFrame) instead of replayed.
        this.skipAudioUntilMs = targetMs;
        this.cachedTimeMs = targetMs;
        this.pendingStartSilence = this.audioDelayMs < 0;
        this.clockOffsetMs = targetMs;
        this.anchorNanos = System.nanoTime();
        this.clockFloorMs = Long.MIN_VALUE; // a seek may legitimately move the clock backwards
        this.singleFrameAfterSeek = this.paused && this.videoCodecContext != null;
    }

    /**
     * The playback clock the picture is paced against. The master is wall time; an output whose
     * device position can be accounted for ({@link AudioOutput#positionTrustworthy()}, e.g. a
     * {@code SourceDataLine}) lets the sound drive it instead, so picture and audio stay tightly
     * glued. Either way the clock is monotonic between seeks and can never stall with a wedged
     * output - a misbehaving sound degrades the sound, never the scene.
     */
    private long clockMs() {
        long value;
        if (this.useAudioClock && this.audioClockValid) {
            long frozenForNanos = System.nanoTime() - this.audioClockStampNanos;
            if (this.paused || frozenForNanos < AUDIO_CLOCK_STALE_NANOS) {
                value = this.audioClockMs;
            } else {
                // The audio clock stopped advancing while playback runs - its thread is blocked in
                // the output - so the picture would freeze with it. Keep moving from the last audible
                // position instead: a stalled sound must never stall the scene (the video then drops
                // the frames it missed, exactly as it does behind a slow decoder).
                value = this.audioClockMs + frozenForNanos / 1_000_000L;
            }
        } else {
            // Wall time: the master clock every player uses. The sound follows as well as the
            // output plays it; the picture never freezes, races or slows with a misbehaving one.
            value = this.clockOffsetMs + (System.nanoTime() - this.anchorNanos) / 1_000_000L;
        }
        // Monotonic between seeks: a step backwards would freeze the picture until it catches up.
        if (value > this.clockFloorMs) {
            this.clockFloorMs = value;
        }
        return this.clockFloorMs;
    }

    // ------------------------------------------------------------------
    // Control surface (client thread - cheap volatile reads/writes only)
    // ------------------------------------------------------------------

    @Override
    public boolean isFinished() {
        return this.finished;
    }

    @Override
    public long timeMs() {
        return this.cachedTimeMs;
    }

    @Override
    public long lengthMs() {
        return this.lengthMs;
    }

    @Override
    public int sourceWidth() {
        return this.sourceWidth;
    }

    @Override
    public int sourceHeight() {
        return this.sourceHeight;
    }

    @Override
    public void seekMs(long positionMs) {
        this.seekRequestMs = Math.max(0L, positionMs);
    }

    @Override
    public long cachedTimeMs() {
        return this.cachedTimeMs;
    }

    @Override
    public void setVolume(int volumePercent) {
        this.volume = Math.max(0.0f, Math.min(1.0f, volumePercent / 100.0f));
        // The output applies the factor per written chunk - it must see this, or a player whose
        // output was created during a silent preload (volume 0) would stay silent forever.
        AudioOutput output = this.audioOutput;
        if (output != null) {
            output.setVolume(this.volume);
            if (this.debug) {
                LOGGER.info("Audio volume set to {}%", volumePercent);
            }
        }
    }

    @Override
    public void setPaused(boolean paused) {
        if (this.paused == paused) {
            return;
        }
        this.paused = paused;
        if (paused) {
            this.pausedAtNanos = System.nanoTime();
            if (this.audioOutput != null) {
                this.audioOutput.stop();
            }
        } else {
            if (this.pausedAtNanos > 0L) {
                // Freeze the wall clock for the duration of the pause.
                this.anchorNanos += System.nanoTime() - this.pausedAtNanos;
                this.pausedAtNanos = 0L;
            }
            if (this.audioOutput != null) {
                this.audioOutput.start();
            }
        }
    }

    @Override
    public void queryTimeMs(java.util.function.LongConsumer consumer) {
        // The cached value is all there is - and it costs nothing, unlike a native query.
        consumer.accept(this.cachedTimeMs);
    }

    @Override
    public void setAudioDelayMs(long delayMs) {
        // Only the audio-only player's sound is lined up with another player's picture.
        if (this.mode == Mode.AUDIO) {
            this.audioDelayMs = delayMs;
            this.pendingStartSilence = delayMs < 0L;
        }
    }

    @Override
    public void setAudioDevice(String device) {
        this.audioDevice = device == null ? "" : device;
    }

    @Override
    public boolean isPreloaded() {
        return this.preloaded && !this.finished;
    }

    @Override
    public void resumeFromPreload() {
        if (this.preloaded) {
            this.preloaded = false;
            setPaused(false);
        }
    }

    @Override
    public boolean consumePauseAfterFirstFrame() {
        if (this.pauseAfterFirstFrame && this.firstFrameSeen) {
            this.pauseAfterFirstFrame = false;
            return true;
        }
        return false;
    }

    @Override
    public void setHardwareDecoding(boolean hardwareDecoding) {
        this.hardwareDecoding = hardwareDecoding;
    }

    @Override
    public void setDebugLogging(boolean debug) {
        this.debug = debug;
        if (FfmpegNativeLibrary.isAvailable()) {
            avutil.av_log_set_level(debug ? avutil.AV_LOG_VERBOSE : avutil.AV_LOG_ERROR);
        }
    }

    @Override
    public VideoFrameSink sink() {
        return this.sink;
    }

    // ------------------------------------------------------------------
    // Teardown
    // ------------------------------------------------------------------

    @Override
    public void close() {
        this.closed = true;
        joinQuietly(this.decodeThread, 5000);
        joinQuietly(this.audioThread, 2000);
        if (this.hwActive && this.presentedFrames == 0) {
            // The hardware path never produced a picture in this session: stop offering it, so
            // the next session (the manager's restart) comes back in software.
            HW_UNUSABLE.set(true);
            LOGGER.warn("Hardware decoding produced no frames - software decoding will be used instead");
        }
        if (this.decodeThread != null && this.decodeThread.isAlive()) {
            // Freeing while the decode thread still runs would crash; leaking the context is the
            // smaller problem (it is reclaimed when the process exits).
            LOGGER.warn("The decoder of {} did not stop in time - leaking its FFmpeg context", this.file);
        } else {
            freeNative();
        }
        this.audioChunks.clear();
        if (this.audioOutput != null) {
            this.audioOutput.close();
            this.audioOutput = null;
        }
        this.decodeThread = null;
        this.audioThread = null;
        this.sink.close();
    }

    private void freeNative() {
        if (this.formatContext != null) {
            avformat.avformat_close_input(this.formatContext);
            this.formatContext = null;
        }
        if (this.videoCodecContext != null) {
            avcodec.avcodec_free_context(this.videoCodecContext);
            this.videoCodecContext = null;
        }
        if (this.audioCodecContext != null) {
            avcodec.avcodec_free_context(this.audioCodecContext);
            this.audioCodecContext = null;
        }
        if (this.packet != null) {
            avcodec.av_packet_free(this.packet);
            this.packet = null;
        }
        if (this.frame != null) {
            avutil.av_frame_free(this.frame);
            this.frame = null;
        }
        releaseScaler();
        if (this.swrContext != null) {
            swresample.swr_free(this.swrContext);
            this.swrContext = null;
        }
        if (this.pcmPlanes != null) {
            this.pcmPlanes.releaseReference();
            this.pcmPlanes = null;
        }
        if (this.pcmBuffer != null) {
            MemoryUtil.memFree(this.pcmBuffer);
            this.pcmBuffer = null;
        }
        if (this.swFrame != null) {
            avutil.av_frame_free(this.swFrame);
            this.swFrame = null;
        }
        if (this.hwDeviceBuf != null) {
            avutil.av_buffer_unref(this.hwDeviceBuf);
            this.hwDeviceBuf = null;
        }
        this.hwActive = false;
        this.hwPixFmt = -1;
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private static void sleepUnchecked(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void joinQuietly(Thread thread, long millis) {
        if (thread == null) {
            return;
        }
        try {
            thread.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** FFmpeg's av_err2str, which is a C macro and has no JavaCPP binding. */
    private static String errorString(int code) {
        byte[] bytes = new byte[128];
        BytePointer buffer = new BytePointer(bytes);
        try {
            avutil.av_strerror(code, buffer, bytes.length);
        } finally {
            buffer.releaseReference();
        }
        int end = 0;
        while (end < bytes.length && bytes[end] != 0) {
            end++;
        }
        return new String(bytes, 0, end, java.nio.charset.StandardCharsets.US_ASCII);
    }
}