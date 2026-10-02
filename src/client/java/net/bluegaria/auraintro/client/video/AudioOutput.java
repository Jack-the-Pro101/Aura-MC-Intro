package net.bluegaria.auraintro.client.video;

import java.util.Locale;

/**
 * The video's audio output, fed with 16-bit PCM by the player's audio thread - whose blocking
 * {@link #write(byte[])} paces the sound feed.
 *
 * <p>Two implementations exist, and {@link #open} picks between them: on Linux the sound goes
 * through {@link PulseAudioOutput} - the system sound server (PulseAudio, or PipeWire's
 * compatibility layer), the exact same route the game's own OpenAL sound takes, so both always
 * land on the same output (headphones vs. speakers) and follow the desktop's default-device
 * switching together. That is also the only route that works inside sandboxed launchers
 * (Flatpak/Snap), where raw ALSA has no sound server behind it. Everywhere else - and on Linux
 * whenever libpulse cannot be loaded - {@link JavaSoundOutput} plays through Java Sound with a
 * deliberate device choice.</p>
 *
 * <p>Whether an implementation's {@link #mediaPositionUs()} is trustworthy enough to pace the
 * picture against is declared by {@link #positionTrustworthy()}; the player's clock selection
 * lives in {@code FfmpegVideoPlayer.clockMs()}.</p>
 */
interface AudioOutput {

    int rate();

    int channels();

    /** Writes one chunk of PCM; blocks while the output's buffer is full. */
    void write(byte[] chunk);

    /**
     * What has actually been heard so far, in microseconds of media time. Unlike byte accounting,
     * this is the real consumption position and is what the picture has to be scheduled against.
     */
    long mediaPositionUs();

    void start();

    /**
     * Called over and over by the audio thread while the player is paused. An output that needs
     * to keep its device busy (see {@link PulseAudioOutput#idle()}) writes a short stretch of
     * silence here, blocking like {@link #write(byte[])}; returns whether it did, so the caller
     * knows whether it has to sleep itself.
     */
    default boolean idle() {
        return false;
    }

    void stop();

    /** Discards the queued samples - used when the playback position jumps (a seek). */
    void flush();

    void close();

    /**
     * Updates the sample-scaling factor; picked up by the next written chunk.
     */
    void setVolume(float volume);

    /**
     * How long a chunk written now takes until its first sample is heard, in milliseconds - the
     * output's whole latency, device included. Outputs whose {@link #mediaPositionUs()} already
     * reflects this (a device position) do not need it and report 0.
     */
    default int nominalLatencyMs() {
        return 0;
    }

    /**
     * Whether {@link #mediaPositionUs()} is a trustworthy playback clock to pace the picture
     * against. A {@code SourceDataLine}'s device position is; a sound server's reported latency
     * is not - after an underrun PipeWire's PulseAudio layer reports 0 forever, and the derived
     * position then leads, lags or stalls depending on the server's internal state. Untrustworthy
     * outputs get wall-clock pacing with the sound playing best-effort under it.
     */
    default boolean positionTrustworthy() {
        return true;
    }

    /**
     * Opens the best available output for the given source format.
     *
     * <p>{@code preferredDevice} names the output to use - on Linux a PulseAudio sink name (as in
     * the desktop's audio widget, e.g. {@code WH-CH520}), or a Java Sound mixer name when the
     * sound server cannot be used; empty or unknown names mean "the system's default output".</p>
     *
     * @return the opened output, or {@code null} when the video has to play silently
     */
    static AudioOutput open(Object context, int preferredRate, int preferredChannels, float volume,
                            boolean debug, String preferredDevice) {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) {
            AudioOutput pulse = PulseAudioOutput.open(context, preferredRate, preferredChannels,
                    volume, debug, preferredDevice);
            if (pulse != null) {
                return pulse;
            }
        }
        return JavaSoundOutput.open(context, preferredRate, preferredChannels, volume, debug,
                preferredDevice);
    }

    /** Scales every 16-bit sample. The factor is never above 1, so no clamping is needed. */
    static void scale(byte[] chunk, float factor) {
        for (int i = 0; i + 1 < chunk.length; i += 2) {
            int sample = (short) ((chunk[i] & 0xFF) | (chunk[i + 1] << 8));
            int scaled = (int) (sample * factor);
            chunk[i] = (byte) scaled;
            chunk[i + 1] = (byte) (scaled >> 8);
        }
    }
}
