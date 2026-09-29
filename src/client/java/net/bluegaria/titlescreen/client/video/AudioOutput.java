package net.bluegaria.titlescreen.client.video;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/**
 * Plays the decoded 16-bit PCM samples through Java Sound's {@link SourceDataLine}.
 *
 * <p>This is the whole audio output of the video: the sound no longer goes through Minecraft's
 * OpenAL engine (the old libVLC backend played it itself), it is simply handed to the operating
 * system's mixer at the configured volume. {@link #write(byte[])} blocks while the operating
 * system's buffer is full, which makes the audio thread the playback clock: as long as decoding
 * keeps the buffer fed, the sound cannot drift, and a picture that falls behind drops frames
 * instead of delaying the sound.</p>
 */
final class AudioOutput {

    private static final Logger LOGGER = LoggerFactory.getLogger("Titlescreen/Video");

    private final SourceDataLine line;
    private final int rate;
    private final int channels;
    private volatile float volume;

    private AudioOutput(SourceDataLine line, int rate, int channels, float volume) {
        this.line = line;
        this.rate = rate;
        this.channels = channels;
        this.volume = volume;
    }

    int rate() {
        return this.rate;
    }

    int channels() {
        return this.channels;
    }

    /**
     * Opens a line for the given source format.
     *
     * <p>The preferred format is the audio track's own rate and channel count; if the operating
     * system's mixer does not have such a line, the usual stereo rates are tried instead (the
     * resampler is configured to whatever format this ends up choosing).</p>
     *
     * @return the opened output, or {@code null} when the video has to play silently
     */
    static AudioOutput open(Object context, int preferredRate, int preferredChannels, float volume, boolean debug) {
        int[][] candidates = {
                {preferredRate, preferredChannels},
                {preferredRate, 2},
                {48000, 2},
                {44100, 2},
        };
        for (int[] candidate : candidates) {
            AudioFormat format = new AudioFormat(candidate[0], 16, candidate[1], true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            if (!AudioSystem.isLineSupported(info)) {
                continue;
            }
            try {
                SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
                // An internal buffer of roughly 125 ms keeps write() from returning early while
                // still making it the pacing clock.
                line.open(format, Math.max(4096, candidate[0] * candidate[1] * 2 / 8));
                line.start();
                AudioOutput output = new AudioOutput(line, candidate[0], candidate[1], volume);
                if (debug) {
                    LOGGER.info("Opened a {} Hz x {} channel audio output for {}",
                            candidate[0], candidate[1], context);
                }
                return output;
            } catch (LineUnavailableException | IllegalArgumentException e) {
                LOGGER.warn("Could not open a {} Hz x {} channel audio output for {}: {}",
                        candidate[0], candidate[1], context, e.toString());
            }
        }
        LOGGER.warn("No audio output is available for {} - the video plays without sound", context);
        return null;
    }

    /** Writes one chunk of PCM; blocks while the operating system's buffer is full. */
    void write(byte[] chunk) {
        float v = this.volume;
        if (v < 0.999f) {
            scale(chunk, v);
        }
        this.line.write(chunk, 0, chunk.length);
    }

    /**
     * What the device has actually played, in microseconds of media time. Unlike byte accounting,
     * this is the real consumption position and is what the picture has to be scheduled against.
     */
    long mediaPositionUs() {
        return this.line.getMicrosecondPosition();
    }

    void start() {
        this.line.start();
    }

    /** Updates the sample-scaling factor; picked up by the next written chunk. */
    void setVolume(float volume) {
        this.volume = Math.max(0.0f, Math.min(1.0f, volume));
    }

    void stop() {
        this.line.stop();
    }

    /** Discards the queued samples - used when the playback position jumps (a seek). */
    void flush() {
        this.line.stop();
        this.line.flush();
        this.line.start();
    }

    void close() {
        try {
            this.line.stop();
            this.line.close();
        } catch (Throwable t) {
            LOGGER.debug("Closing the audio output failed", t);
        }
    }

    /** Scales every 16-bit sample. The factor is never above 1, so no clamping is needed. */
    private static void scale(byte[] chunk, float factor) {
        for (int i = 0; i + 1 < chunk.length; i += 2) {
            int sample = (short) ((chunk[i] & 0xFF) | (chunk[i + 1] << 8));
            int scaled = (int) (sample * factor);
            chunk[i] = (byte) scaled;
            chunk[i + 1] = (byte) (scaled >> 8);
        }
    }
}