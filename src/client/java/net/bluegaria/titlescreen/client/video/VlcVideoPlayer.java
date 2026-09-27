package net.bluegaria.titlescreen.client.video;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.TrackDescription;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;
import uk.co.caprica.vlcj.player.embedded.videosurface.CallbackVideoSurface;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback;
import uk.co.caprica.vlcj.media.VideoTrackInfo;

import org.lwjgl.system.MemoryUtil;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import java.nio.ByteBuffer;
import java.nio.file.Files;
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
        if (volumePercent <= 0) {
            // Only start re-asserting silence once the preload flag is set: the loop returns immediately
            // otherwise, which is why the preload was occasionally audible for its first moments.
            keepPreloadSilent();
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
            postCommand(() -> setAudioEnabled(this.player, true));
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
                this.factory = new MediaPlayerFactory(playerArguments(false));
                this.player = this.factory.mediaPlayers().newEmbeddedMediaPlayer();
                FormatCallback formatCallback = new FormatCallback();
                CallbackVideoSurface surface = this.factory.videoSurfaces()
                        .newVideoSurface(formatCallback, new FrameCallback(formatCallback), false);
                this.player.videoSurface().set(surface);
                this.player.events().addMediaPlayerEventListener(new PlayerEvents(file));

                if (volumePercent <= 0) {
                    // Neither the volume nor mute is honoured before libVLC has created its audio output,
                    // but the audio track switch is - so a preload that plays (and therefore decodes the
                    // frames the window opens onto) can still be silent.
                    setAudioEnabled(this.player, false);
                }
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

    /**
     * Switches the audio track on or off, so a player can run (and decode) silently.
     *
     * <p>Needed because the volume and mute settings are ignored until libVLC has created its audio output -
     * the reason the preload used to be started paused, which in turn meant no frame was decoded until the
     * loading screen resumed it. A track switch is honoured from the start.</p>
     */
    private static void setAudioEnabled(MediaPlayer mediaPlayer, boolean enabled) {
        if (mediaPlayer == null) {
            return;
        }
        try {
            if (enabled) {
                for (TrackDescription description : mediaPlayer.audio().trackDescriptions()) {
                    if (description.id() >= 0) {
                        mediaPlayer.audio().setTrack(description.id());
                        return;
                    }
                }
            } else {
                mediaPlayer.audio().setTrack(-1);
            }
        } catch (Throwable t) {
            LOGGER.debug("Could not switch the audio track (enabled={})", enabled, t);
        }
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
    private void keepPreloadSilent() {
        postCommand(() -> {
            EmbeddedMediaPlayer current = this.player;
            if (!this.preloaded || current == null) {
                return;
            }
            setAudioEnabled(current, false);
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
            keepPreloadSilent();
        });
    }

    /** libVLC command line for the media players. */
    private String[] playerArguments(boolean startPaused) {
        List<String> arguments = new ArrayList<>(List.of("--no-video-title-show"));
        if (startPaused) {
            // The only reliable way to keep a preload silent: volume and mute are both ignored until libVLC
            // has created its audio output, so a preload would otherwise play its first moments out loud
            // while the game window is still coming up.
            arguments.add("--start-paused");
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
            if (this.audioReported) {
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
