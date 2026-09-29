package net.bluegaria.titlescreen.client.video;

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
     * Runs a playback-time query on the player's own thread and hands the result to the consumer.
     *
     * <p>Meant for diagnostics: the returned value is the time the player itself is at (the
     * displayed picture / the played sound), not the cached event value.</p>
     *
     * @param consumer receives the position in milliseconds, or {@code -1} when unknown
     */
    default void queryTimeMs(java.util.function.LongConsumer consumer) {
        consumer.accept(-1L);
    }

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

    /** Enables verbose backend logging (used by the "debug logging" config option). */
    default void setDebugLogging(boolean debug) {
    }

    VideoFrameSink sink();

    void close();
}
