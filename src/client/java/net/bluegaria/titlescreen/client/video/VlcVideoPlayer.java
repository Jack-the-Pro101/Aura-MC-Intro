package net.bluegaria.titlescreen.client.video;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;
import uk.co.caprica.vlcj.player.embedded.videosurface.CallbackVideoSurface;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback;
import uk.co.caprica.vlcj.media.VideoTrackInfo;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * libVLC based {@link VideoPlayer} using vlcj's direct (callback) video output.
 *
 * <p>Requesting the {@code RGBA} chroma makes libVLC hand us frames with a real alpha channel,
 * which is what allows transparent WebM/VP9 videos to be composited over the title screen.
 * Audio is played by libVLC itself, so nothing has to be mixed into Minecraft's sound engine.</p>
 */
public final class VlcVideoPlayer implements VideoPlayer {

    /**
     * What this player does with the media.
     *
     * <p>A baked video (one file for the loading scene and the intro) is played by two players: the picture
     * by a silent video player, the sound by an audio-only one. That is deliberate - separate players have
     * separate demux and decoder threads. The video pipeline cannot always keep up while the game is loading
     * a resource pack, and in a combined pipeline the late video delays the audio packets with it, which is
     * heard as the sound cutting out. The picture may drop frames; the sound will not.</p>
     */
    public enum Mode {
        /** Video through the callback vout, audio played by libVLC (an intro video on its own). */
        VIDEO,
        /** Video through the callback vout, audio output disabled. */
        SILENT_VIDEO,
        /** Audio only: no video output and no frame callbacks. */
        AUDIO
    }

    private final Mode mode;

    public VlcVideoPlayer() {
        this(Mode.VIDEO);
    }

    private VlcVideoPlayer(Mode mode) {
        this.mode = mode;
    }

    /** A video player whose audio output is disabled - see {@link Mode#SILENT_VIDEO}. */
    public static VlcVideoPlayer silentVideo() {
        return new VlcVideoPlayer(Mode.SILENT_VIDEO);
    }

    /** An audio-only player, independent of any video player - see {@link Mode#AUDIO}. */
    public static VlcVideoPlayer audioOnly() {
        return new VlcVideoPlayer(Mode.AUDIO);
    }

    private boolean hasVideoOutput() {
        return this.mode != Mode.AUDIO;
    }

    private boolean hasAudioOutput() {
        return this.mode != Mode.SILENT_VIDEO;
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("Titlescreen/Video");
    private static final String CHROMA = "RGBA";
    private static final String RV32_PREFIX = "RV32";

    private final VideoFrameSink sink = new VideoFrameSink();

    /** True while a background thread is tearing this player down - see {@link #close()}. */
    private final AtomicBoolean closing = new AtomicBoolean();

    /**
     * Serialises the native libVLC calls that create or destroy a player. Two players exist during the
     * hand-over from the loading screen to the title screen (the frozen background plus the intro), and
     * creating one while the other is being torn down inside JNA's marshalling is the kind of overlap
     * that produces a rare hard freeze. Creation only *tries* to take it (see {@code open}) so a
     * teardown that hangs inside libVLC cannot stop a new player from starting.
     */
    private static final ReentrantLock PLAYER_LOCK = new ReentrantLock();

    private MediaPlayerFactory factory;
    private EmbeddedMediaPlayer player;

    private volatile boolean finished;
    private volatile long lengthMs;
    /** Cached from libVLC's timeChanged event - see {@link #cachedTimeMs()}. */
    private volatile long cachedTimeMs = -1L;
    private volatile int pendingVolume;
    private volatile boolean debug;
    private volatile boolean hardwareDecoding = true;
    private volatile boolean preloaded;

    /**
     * All libVLC control calls (volume, mute, audio track, pause) run here, one at a time.
     *
     * <p>libVLC's audio functions block while its audio output is being created, and calling them from
     * inside a libVLC event callback - which is what the {@code playing} handler used to do - or from the
     * render thread at the same moment deadlocked the whole client: a thread dump showed both the
     * {@code media-player-events} thread and the render thread stuck in {@code libvlc_audio_set_volume}.
     * Serialising them on one thread, never on the game's own threads, removes both halves of that.</p>
     */
    private final ExecutorService commands = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Titlescreen-Video-Commands");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean pauseAfterFirstFrame;
    private volatile boolean firstFrameSeen;
    private boolean sourceSizeLogged;
    private int sourceWidth;
    private int sourceHeight;

    @Override
    public boolean preload(Path file, int volumePercent, String libVlcPath) {
        this.pauseAfterFirstFrame = true;
        boolean ok = open(file, volumePercent, libVlcPath);
        this.preloaded = ok;
        if (!ok) {
            this.pauseAfterFirstFrame = false;
            return false;
        }
        if (hasAudioOutput() && volumePercent <= 0) {
            // Silence comes from the startup volume (see playerArguments) plus this loop, never from
            // switching the audio track off: that also stops libVLC from creating its audio output at all,
            // and creating it when the scene begins costs about a second of sound *and* the same second of
            // delay, because the samples queued from the start are then played late. A player that starts
            // at volume 0 has a live, silent output that only has to be turned up. Only start re-asserting
            // silence once the preload flag is set - the loop returns immediately otherwise, which is why
            // the preload was occasionally audible for its first moments.
            keepPreloadMuted();
        }
        return true;
    }

    @Override
    public boolean isPreloaded() {
        return this.preloaded;
    }

    @Override
    public void resumeFromPreload() {
        if (this.preloaded) {
            this.preloaded = false;
            this.pauseAfterFirstFrame = false;
            setPaused(false);
        }
    }

    /** Called from the client thread once the first preload frame arrived. */
    @Override
    public boolean consumePauseAfterFirstFrame() {
        if (this.pauseAfterFirstFrame && this.firstFrameSeen) {
            this.pauseAfterFirstFrame = false;
            return true;
        }
        return false;
    }

    @Override
    public boolean start(Path file, int volumePercent, String libVlcPath) {
        return open(file, volumePercent, libVlcPath);
    }

    private synchronized boolean open(Path file, int volumePercent, String libVlcPath) {
        this.pendingVolume = volumePercent;
        try {
            if (!VlcNativeLibrary.prepare(libVlcPath)) {
                String hint = VlcNativeLibrary.sandboxHint();
                LOGGER.warn("libvlc could not be located. Install VLC or set 'libVLC folder' in the titlescreen "
                        + "config to a directory containing libvlc. The mod keeps working without it.");
                if (hint != null) {
                    LOGGER.warn(hint);
                }
                close();
                return false;
            }

            boolean locked = false;
            try {
                locked = PLAYER_LOCK.tryLock(2L, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (!locked) {
                // A previous player is stuck in libVLC's teardown - better to start this one without the
                // lock than to never start it at all.
                LOGGER.warn("libVLC is still tearing down another player - starting the video anyway");
            }
            try {
                this.factory = new MediaPlayerFactory(playerArguments(volumePercent <= 0));
                this.player = this.factory.mediaPlayers().newEmbeddedMediaPlayer();
                if (hasVideoOutput()) {
                    FormatCallback formatCallback = new FormatCallback();
                    CallbackVideoSurface surface = this.factory.videoSurfaces()
                            .newVideoSurface(formatCallback, new FrameCallback(formatCallback), false);
                    this.player.videoSurface().set(surface);
                }
                this.player.events().addMediaPlayerEventListener(new PlayerEvents(file));

                if (!this.player.media().play(file.toAbsolutePath().toString())) {
                    LOGGER.warn("libvlc refused to play {}", file);
                    close();
                    return false;
                }
            } finally {
                if (locked) {
                    PLAYER_LOCK.unlock();
                }
            }
            postCommand(this::applyPendingVolume);
            return true;
        } catch (Throwable t) {
            LOGGER.warn("Failed to start video playback for {} - the video intro will be skipped", file, t);
            close();
            return false;
        }
    }

    /** One-shot debug dump of the padded buffer size libVLC hands out. */
    private void logFormatOnce(BufferFormat format, int bufferWidth, int bufferHeight) {
        if (!this.debug || this.sourceSizeLogged) {
            return;
        }
        this.sourceSizeLogged = true;
        LOGGER.info("libVLC video buffers: chroma={} buffer={}x{} (decoded frames are scaled by libVLC "
                        + "to this size; the true aspect ratio comes from the track size)",
                format.getChroma(), bufferWidth, bufferHeight);
    }

    /** Runs a libVLC control call on the command thread, swallowing whatever it throws. */
    private void postCommand(Runnable command) {
        try {
            this.commands.execute(() -> {
                try {
                    command.run();
                } catch (Throwable t) {
                    LOGGER.debug("A queued libVLC call failed", t);
                }
            });
        } catch (Throwable t) {
            LOGGER.debug("Could not queue a libVLC call", t);
        }
    }

    /** Re-applies the configured volume on the command thread. */
    private void applyPendingVolume() {
        EmbeddedMediaPlayer current = this.player;
        if (current == null) {
            return;
        }
        current.audio().setVolume(this.pendingVolume);
        if (this.pendingVolume > 0) {
            current.audio().setMute(false);
        }
    }

    /**
     * Keeps a playing preload silent until it is resumed.
     *
     * <p>Switching the audio track off <em>before</em> playback is what makes a preload silent, but libVLC
     * creates its audio output asynchronously - so depending on the timing the track switch could land too
     * early and the first moments were audible. Re-asserting silence on the command thread (the same thread
     * every other control call uses) until the preload is resumed closes that race; the resume clears
     * {@link #preloaded} before it queues anything, so this can never mute the real audio.</p>
     */
    private void keepPreloadMuted() {
        postCommand(() -> {
            EmbeddedMediaPlayer current = this.player;
            if (!this.preloaded || current == null) {
                return;
            }
            current.audio().setVolume(0);
            current.audio().setMute(true);
            if (!this.preloaded || this.player == null) {
                return;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            keepPreloadMuted();
        });
    }

    /** libVLC command line for the media players. */
    private String[] playerArguments(boolean silentStart) {
        List<String> arguments = new ArrayList<>(List.of("--no-video-title-show"));
        if (!this.hasVideoOutput()) {
            arguments.add("--no-video");
        }
        if (!this.hasAudioOutput()) {
            arguments.add("--no-audio");
        }
        if (silentStart && hasAudioOutput()) {
            // Start silent, but with an audio output that exists: resuming then costs nothing but turning
            // the volume up, instead of creating the output (about a second of silence, and the same
            // second of delay because libVLC plays the queued samples from the start).
            arguments.add("--volume=0");
        }
        if (!this.debug) {
            arguments.add("--quiet");
        }
        // With debug logging on, libVLC is left at its default verbosity: only its own errors and
        // warnings are printed (a "--verbose=2" there would bury the log in decoder/plugin messages,
        // and the mod already reports buffer sizes, audio state and frame rates itself).
        if (this.hardwareDecoding) {
            // Software decoding of a 4K source cannot keep up (VLC's default is "--avcodec-hw=none"),
            // which shows up as a video that stutters and then ends after a handful of frames.
            // libVLC falls back to the software decoder when the driver cannot handle the stream.
            arguments.add("--avcodec-hw=any");
        }
        return arguments.toArray(new String[0]);
    }
    @Override
    public boolean isFinished() {
        return this.finished;
    }

    @Override
    public long cachedTimeMs() {
        return this.cachedTimeMs;
    }

    @Override
    public long timeMs() {
        EmbeddedMediaPlayer current = this.player;
        return current == null ? 0L : current.status().time();
    }

    @Override
    public long lengthMs() {
        // Only the value reported by libVLC's lengthChanged event is returned: the client thread asks
        // for this while drawing, and querying libVLC there can block when the player is in a bad state
        // (the same class of native call that froze the render thread once already).
        return Math.max(0L, this.lengthMs);
    }

    @Override
    public int sourceWidth() {
        // Cached only - see cacheSourceSize. The render thread asks for this every frame and must never
        // make a native call.
        return this.sourceWidth;
    }

    @Override
    public int sourceHeight() {
        return this.sourceHeight;
    }

    /**
     * Caches the true (unpadded) track dimensions from libVLC's media info.
     *
     * <p>Only ever called from libVLC's own event thread: asking libVLC for media info from the client
     * thread is a native call that can block and freeze the whole game. Until it is known, callers fall
     * back to the visible buffer size, which has the same aspect ratio.</p>
     */
    private void cacheSourceSize(MediaPlayer mediaPlayer) {
        if (this.sourceWidth > 0 && this.sourceHeight > 0) {
            return;
        }
        try {
            java.util.List<VideoTrackInfo> tracks = mediaPlayer.media().info().videoTracks();
            if (!tracks.isEmpty()) {
                VideoTrackInfo track = tracks.get(0);
                if (track.width() > 0 && track.height() > 0) {
                    this.sourceWidth = track.width();
                    this.sourceHeight = track.height();
                }
            }
        } catch (Throwable ignored) {
            // Media info is not ready yet - the next event tries again.
        }
    }

    @Override
    public void queryTimeMs(java.util.function.LongConsumer consumer) {
        // Only the value cached from libVLC's own events is reported. Querying the player itself is a native
        // call on the command thread - the same class of call that once froze the render thread - and the
        // event value is plenty for reporting how far the picture and the sound are apart.
        consumer.accept(this.cachedTimeMs);
    }

    @Override
    public void setAudioDelayMs(long delayMs) {
        if (this.mode != Mode.AUDIO) {
            // Only the audio-only player's sound is lined up with another player's picture.
            return;
        }
        postCommand(() -> {
            EmbeddedMediaPlayer current = this.player;
            if (current != null) {
                // libVLC's audio delay is in microseconds, not milliseconds.
                current.audio().setDelay(delayMs * 1000L);
            }
        });
    }

    @Override
    public void seekMs(long positionMs) {
        this.finished = false;
        postCommand(() -> {
            EmbeddedMediaPlayer current = this.player;
            if (current != null) {
                current.controls().setTime(Math.max(0L, positionMs));
            }
        });
    }

    @Override
    public void setVolume(int volumePercent) {
        this.pendingVolume = volumePercent;
        postCommand(this::applyPendingVolume);
    }

    @Override
    public void setPaused(boolean paused) {
        postCommand(() -> {
            EmbeddedMediaPlayer current = this.player;
            if (current != null) {
                current.controls().setPause(paused);
            }
        });
    }

    @Override
    public void setDebugLogging(boolean debug) {
        this.debug = debug;
    }

    @Override
    public void setHardwareDecoding(boolean hardwareDecoding) {
        this.hardwareDecoding = hardwareDecoding;
    }


    @Override
    public VideoFrameSink sink() {
        return this.sink;
    }

    @Override
    public void close() {
        EmbeddedMediaPlayer current = this.player;
        this.player = null;
        this.finished = false;
        MediaPlayerFactory currentFactory = this.factory;
        this.factory = null;
        if (current == null && currentFactory == null) {
            return;
        }
        if (!this.closing.compareAndSet(false, true)) {
            return;
        }
        // libvlc_media_player_stop() waits for libVLC's own threads and can block indefinitely when the
        // player is already in a bad state (a stalled video output, for example) - and calling it from
        // the client thread freezes the entire game (documented by a thread dump of a real freeze).
        // The teardown therefore happens on a daemon thread: the game simply carries on without this
        // player, and the sink is only released once libVLC is really done with it.
        Thread thread = new Thread(() -> {
            try {
                PLAYER_LOCK.lock();
                try {
                    if (current != null) {
                        current.controls().stop();
                        current.release();
                    }
                    if (currentFactory != null) {
                        currentFactory.release();
                    }
                } finally {
                    PLAYER_LOCK.unlock();
                }
                this.sink.close();
            } catch (Throwable t) {
                LOGGER.debug("Error while tearing down the libvlc player", t);
            } finally {
                this.closing.set(false);
            }
        }, "Titlescreen-Video-Teardown");
        thread.setDaemon(true);
        thread.start();
    }

    /** Forwards libVLC player events into our simple state flags. */
    private final class PlayerEvents extends MediaPlayerEventAdapter {
        private final Path file;
        private boolean audioReported;
        private boolean audioVolumeLogged;

        private PlayerEvents(Path file) {
            this.file = file;
        }

        @Override
        public void mediaPlayerReady(MediaPlayer mediaPlayer) {
            postCommand(VlcVideoPlayer.this::applyPendingVolume);
        }




        @Override
        public void playing(MediaPlayer mediaPlayer) {
            // The audio output only exists once playback starts, so (re)apply the volume - but from the
            // command thread: calling it from inside libVLC's event dispatch deadlocked the client.
            postCommand(VlcVideoPlayer.this::applyPendingVolume);
            postCommand(() -> logAudioStateOnce(VlcVideoPlayer.this.player));
            cacheSourceSize(mediaPlayer);
            if (VlcVideoPlayer.this.mode == Mode.AUDIO) {
                // An audio-only player has no video callback to report its first samples, and the preload
                // pauses on them so it cannot run ahead of the scene that shows the video.
                VlcVideoPlayer.this.firstFrameSeen = true;
            }
        }

        @Override
        public void timeChanged(MediaPlayer mediaPlayer, long newTime) {
            cachedTimeMs = newTime;
            cacheSourceSize(mediaPlayer);
            // The audio output is created a moment after playback starts - report its state the first
            // time libVLC actually knows the volume (before that it reports -1/muted, which is not an
            // error and only confuses whoever reads the log).
            if (this.audioVolumeLogged) {
                return;
            }
            this.audioVolumeLogged = true;
            postCommand(() -> logAudioStateOnce(VlcVideoPlayer.this.player));
        }

        /**
         * One-shot report of the video's audio tracks. A video without an audio track is by far the
         * most common reason for "the video has no sound", so say it out loud instead of leaving the
         * user guessing.
         */
        private void logAudioStateOnce(MediaPlayer mediaPlayer) {
            if (this.audioReported || VlcVideoPlayer.this.mode == Mode.SILENT_VIDEO
                    || !VlcVideoPlayer.this.debug) {
                // Nothing worth reporting: the debug option is off, or this player has no audio output.
                return;
            }
            this.audioReported = true;
            int tracks;
            try {
                tracks = mediaPlayer.media().info().audioTracks().size();
            } catch (Throwable t) {
                return;
            }
            if (tracks == 0) {
                LOGGER.info("{} has no audio track - nothing will be heard for this video",
                        this.file.getFileName());
                return;
            }
            LOGGER.info("{} has {} audio track(s), requested volume {}%",
                    this.file.getFileName(), tracks, pendingVolume);
        }

        @Override
        public void lengthChanged(MediaPlayer mediaPlayer, long newLength) {
            lengthMs = newLength;
        }

        @Override
        public void finished(MediaPlayer mediaPlayer) {
            finished = true;
        }

        @Override
        public void error(MediaPlayer mediaPlayer) {
            finished = true;
            LOGGER.warn("libvlc reported a playback error for {}", this.file);
        }
    }

    /** Tells libVLC which chroma/stride we want - RGBA keeps the alpha channel intact. */
    private final class FormatCallback implements BufferFormatCallback {

        private volatile int visibleWidth = -1;
        private volatile int visibleHeight = -1;

        @Override
        public BufferFormat getBufferFormat(int sourceWidth, int sourceHeight) {
            // Asking libVLC for an already scaled buffer is what keeps large sources (4K and up)
            // cheap: VLC scales during its own conversion, so we neither copy a 33 MB frame nor run
            // a per-pixel loop in Java. libVLC may still pad the buffer, and it reports the visible
            // picture size through newFormatSize().
            // Always the native size: no scaling inside libVLC, so a 4K video stays 4K on a 4K display
            // (and libVLC's scaler, which crashed natively more than once, is never involved).
            return new BufferFormat(CHROMA, sourceWidth, sourceHeight,
                    new int[]{sourceWidth * 4}, new int[]{sourceHeight});
        }

        @Override
        public void newFormatSize(int width, int height, int visibleWidth, int visibleHeight) {
            this.visibleWidth = visibleWidth;
            this.visibleHeight = visibleHeight;
        }

        @Override
        public void allocatedBuffers(ByteBuffer[] buffers) {
            // Buffers are owned by libVLC; we copy out of them.
        }
    }

    /** Receives decoded frames on a libVLC thread and stages them for the render thread. */
    private final class FrameCallback implements RenderCallback {

        private final FormatCallback format;

        private FrameCallback(FormatCallback format) {
            this.format = format;
        }

        @Override
        public void lock(MediaPlayer mediaPlayer) {
            // No locking needed - we copy the frame out immediately.
        }

        @Override
        public void unlock(MediaPlayer mediaPlayer) {
            // See lock().
        }

        @Override
        public void display(MediaPlayer mediaPlayer, ByteBuffer[] buffers, BufferFormat bufferFormat,
                            int displayWidth, int displayHeight) {
            if (buffers.length == 0 || buffers[0] == null) {
                return;
            }
            logFormatOnce(bufferFormat, displayWidth, displayHeight);
            int[] pitches = bufferFormat.getPitches();
            int pitch = pitches.length > 0 ? pitches[0] : displayWidth * 4;
            boolean bgrFallback = bufferFormat.getChroma() != null
                    && bufferFormat.getChroma().startsWith(RV32_PREFIX);
            VlcVideoPlayer.this.firstFrameSeen = true;
            int visibleWidth = this.format.visibleWidth;
            int visibleHeight = this.format.visibleHeight;
            sink.offerFrame(buffers[0], pitch, displayWidth, displayHeight,
                    visibleWidth, visibleHeight, bgrFallback);
        }
    }
}
