package net.bluegaria.auraintro.client.video;

import java.nio.file.Path;

/**
 * Minimal abstraction over the video backend. Only the bundled-FFmpeg backend is implemented,
 * but keeping this interface means another backend can be added without touching the mixins,
 * the config or the title screen logic.
 */
public interface VideoPlayer {

    /**
     * Opens and starts the given file.
     *
     * @param file          absolute path to the video file
     * @param volumePercent 0-100
     * @return {@code true} when playback could be started
     */
    boolean start(Path file, int volumePercent);

    boolean isFinished();

    long timeMs();

    long lengthMs();

    /**
     * Real width/height of the video track, or 0 while unknown. These are the dimensions to use
     * for aspect ratio maths.
     */
    default int sourceWidth() {
        return 0;
    }

    default int sourceHeight() {
        return 0;
    }

    void seekMs(long positionMs);

    /**
     * Makes the backend loop {@code [startMs, endMs]} on its own: right after the region's last frame
     * has had its screen time, playback continues from {@code startMs} - frame-exact, with no
     * client-tick latency and no jump in the picture's timeline. {@code endMs} 0 means the end of the
     * media; a negative {@code startMs} turns looping off. Playback before {@code startMs} is not
     * affected (the region only decides where the end wraps to).
     */
    default void setLoopRegion(long startMs, long endMs) {
    }

    /** How often the loop region has wrapped around since the player started (see {@link #setLoopRegion}). */
    default int loopCount() {
        return 0;
    }

    /**
     * Last playback position the backend reported, cached from its decode threads. Pure getter -
     * the client thread must never trigger work inside the backend here.
     *
     * @return the cached position in milliseconds, or {@code -1} when unknown
     */
    default long cachedTimeMs() {
        return -1L;
    }

    void setVolume(int volumePercent);

    void setPaused(boolean paused);

    /**
     * Shifts this player's sound relative to its picture, in milliseconds (positive plays it later).
     * Only meaningful for an audio-only player: it is how the sound is lined up with a picture that a
     * separate player produces.
     */
    default void setAudioDelayMs(long delayMs) {
    }

    /**
     * Names the output device the video's sound should play through. On Linux - where the sound
     * goes through the system sound server, like the game's own sound - this is a sink name as
     * shown by the desktop's audio widget (e.g. {@code WH-CH520}); where Java Sound is used
     * instead, it is a mixer name from
     * {@link javax.sound.sampled.AudioSystem#getMixerInfo()}. Empty or unknown names follow the
     * system's default output. Must be called before {@link #start}.
     */
    default void setAudioDevice(String device) {
    }

    /**
     * Opens the file and decodes the first frame, then waits paused. Used so that the loading
     * screen background can be on screen the instant the loading screen appears.
     */
    default boolean preload(Path file, int volumePercent) {
        return false;
    }

    /** True while a preloaded (paused, first frame decoded) session is waiting to be resumed. */
    default boolean isPreloaded() {
        return false;
    }

    /**
     * True once a preloaded session is actually parked on the frame it exists to show (the first
     * picture for a video player, the first sound chunk for an audio-only one), rather than still
     * decoding it. Resuming any earlier would put nothing on screen.
     */
    default boolean isPreloadParked() {
        return true;
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
     * Lets the backend use hardware decoding when the platform provides it, with an automatic
     * fallback to software decoding when it does not work.
     */
    default void setHardwareDecoding(boolean hardwareDecoding) {
    }

    /**
     * Delays the picture by the given milliseconds relative to the sound pipeline's reported
     * position - compensation for an output whose latency the program cannot measure. Bluetooth
     * headphones are the common case: the audio chain buffers far more sound than the device
     * position reports, so without compensation the picture runs ahead of what is actually
     * audible. Zero keeps the picture on the reported position.
     */
    default void setAudioLatencyMs(int latencyMs) {
    }

    /** Enables verbose backend logging (used by the "debug logging" config option). */
    default void setDebugLogging(boolean debug) {
    }

    VideoFrameSink sink();

    void close();

    /** Whether {@link #close()} was called - a closed player can never be opened again. */
    boolean isClosed();

    /** A new, unopened player of the same kind, to take a closed one's place. */
    VideoPlayer fresh();
}
