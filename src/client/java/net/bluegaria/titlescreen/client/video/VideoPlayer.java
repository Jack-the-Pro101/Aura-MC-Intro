package net.bluegaria.titlescreen.client.video;

import java.nio.file.Path;

/**
 * Minimal abstraction over the video backend. Only libVLC is implemented, but keeping this
 * interface means another backend (for example libmpv) can be added without touching the
 * mixins, the config or the title screen logic.
 */
public interface VideoPlayer {

    /**
     * Opens and starts the given file.
     *
     * @param file            absolute path to the video file
     * @param volumePercent   0-100
     * @param libVlcPath      optional directory containing libvlc, may be {@code null}/empty
     * @return {@code true} when playback could be started
     */
    boolean start(Path file, int volumePercent, String libVlcPath);

    boolean isFinished();

    long timeMs();

    long lengthMs();

    /**
     * Real (unpadded) width/height of the video track, or 0 while unknown. libVLC scales the
     * decoded picture into an alignment-padded buffer, so these are the dimensions to use for
     * aspect ratio maths.
     */
    default int sourceWidth() {
        return 0;
    }

    default int sourceHeight() {
        return 0;
    }

    void seekMs(long positionMs);

    /**
     * Last playback position libVLC reported, cached from its events. Pure getter - the client thread
     * must never call {@link #timeMs()} (that is a native call which can block).
     *
     * @return the cached position in milliseconds, or {@code -1} when unknown
     */
    default long cachedTimeMs() {
        return -1L;
    }

    void setVolume(int volumePercent);

    void setPaused(boolean paused);

    /**
     * Opens the file and decodes the first frame, then waits paused. Used so that the loading
     * screen background can be on screen the instant the loading screen appears.
     */
    default boolean preload(Path file, int volumePercent, String libVlcPath) {
        return false;
    }

    /** True while a preloaded (paused, first frame decoded) session is waiting to be resumed. */
    default boolean isPreloaded() {
        return false;
    }

    /** Starts playback of a preloaded session. */
    default void resumeFromPreload() {
    }

    /**
     * One-shot flag: the first preload frame has arrived and the player should now be paused so the
     * frame can be shown while the rest of the game finishes loading.
     */
    default boolean consumePauseAfterFirstFrame() {
        return false;
    }

    /**
     * Lets the backend feed the frames through hardware decoding when it can. Video is then decoded on
     * the GPU, which is what makes 4K sources playable at all.
     */
    default void setHardwareDecoding(boolean hardwareDecoding) {
    }

    /** Enables verbose backend logging (used by the "debug logging" config option). */
    default void setDebugLogging(boolean debug) {
    }

    VideoFrameSink sink();

    void close();
}
