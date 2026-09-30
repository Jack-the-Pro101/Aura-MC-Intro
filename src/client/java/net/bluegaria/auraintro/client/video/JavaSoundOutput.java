package net.bluegaria.auraintro.client.video;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Plays the decoded 16-bit PCM samples through Java Sound's {@link SourceDataLine} - the fallback
 * whenever the system sound server cannot be used ({@link PulseAudioOutput} is preferred on Linux).
 *
 * <p>Device choice matters here: on Linux, Java Sound's ALSA backend lists the sound-server device
 * (PulseAudio/PipeWire - the one that follows the desktop's "default output" setting, i.e.
 * headphones vs. speakers) alongside raw hardware devices, and a process that cannot reach the
 * sound server - a sandboxed launcher, for example - sees only the raw ones, which play through
 * whatever jack they are wired to and ignore the desktop's device switching. {@link #open} tries
 * the mixers in a deliberate order - configured name, then server-backed, then raw hardware - so
 * the fallback still lands as close to the system's default output as it can.</p>
 */
final class JavaSoundOutput implements AudioOutput {

    private static final Logger LOGGER = LoggerFactory.getLogger("Aura-Intro/Video");

    private final SourceDataLine line;
    private final int rate;
    private final int channels;
    private volatile float volume;

    private JavaSoundOutput(SourceDataLine line, int rate, int channels, float volume) {
        this.line = line;
        this.rate = rate;
        this.channels = channels;
        this.volume = volume;
    }

    @Override
    public int rate() {
        return this.rate;
    }

    @Override
    public int channels() {
        return this.channels;
    }

    /**
     * Opens a line for the given source format.
     *
     * <p>The preferred format is the audio track's own rate and channel count; if the operating
     * system's mixer does not have such a line, the usual stereo rates are tried instead (the
     * resampler is configured to whatever format this ends up choosing). {@code preferredMixer}
     * (a mixer name from {@link AudioSystem#getMixerInfo()}, empty for automatic) is tried first
     * when set.</p>
     *
     * @return the opened output, or {@code null} when the video has to play silently
     */
    static JavaSoundOutput open(Object context, int preferredRate, int preferredChannels, float volume,
                                boolean debug, String preferredMixer) {
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
            for (Mixer.Info mixerInfo : candidateMixers(info, preferredMixer)) {
                SourceDataLine line = null;
                try {
                    line = (SourceDataLine) AudioSystem.getMixer(mixerInfo).getLine(info);
                    // An internal buffer of roughly 250 ms keeps short decode/thread stalls from being
                    // heard as gaps, while write() still returns early enough to be the pacing clock.
                    line.open(format, Math.max(4096, candidate[0] * candidate[1] * 2 / 4));
                    line.start();
                    JavaSoundOutput output = new JavaSoundOutput(line, candidate[0], candidate[1], volume);
                    if (debug) {
                        LOGGER.info("Opened a {} Hz x {} channel audio output on mixer '{}' for {}",
                                candidate[0], candidate[1], mixerInfo.getName(), context);
                    }
                    if (isRawHardware(mixerInfo)) {
                        // Raw hardware ignores the desktop's default-output switching (headphones vs.
                        // speakers): reached only when nothing backed by the sound server could be
                        // opened. That is usually where the game's own sound would fall back to as
                        // well, but a line in the log explains any "came out of the speakers" report,
                        // and the mixer names are what the config's audio output device field wants.
                        LOGGER.warn("The video's sound for {} plays through the raw device '{}' - it will "
                                        + "not follow the system's default output setting. Available mixers: {}",
                                context, mixerInfo.getName(), mixerNames());
                    }
                    return output;
                } catch (LineUnavailableException | IllegalArgumentException e) {
                    LOGGER.warn("Could not open {} Hz x {} channel audio on mixer '{}' for {}: {}",
                            candidate[0], candidate[1], mixerInfo.getName(), context, e.toString());
                    if (line != null) {
                        try {
                            line.close();
                        } catch (Throwable ignored) {
                            // Opening failed; closing must not mask the original reason.
                        }
                    }
                }
            }
        }
        LOGGER.warn("No audio output is available for {} - the video plays without sound", context);
        return null;
    }

    /**
     * The mixers that can play the given format, best first: a configured name, then the
     * sound-server-backed devices (which follow the operating system's default output), then
     * raw hardware. Ties keep the JVM's own enumeration order.
     */
    private static List<Mixer.Info> candidateMixers(DataLine.Info info, String preferredMixer) {
        List<Mixer.Info> supported = new ArrayList<>();
        for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
            try {
                if (AudioSystem.getMixer(mixerInfo).isLineSupported(info)) {
                    supported.add(mixerInfo);
                }
            } catch (Throwable ignored) {
                // A mixer that cannot be queried is a mixer that cannot be opened either.
            }
        }
        String want = preferredMixer == null ? "" : preferredMixer.trim();
        supported.sort(Comparator.comparingInt((Mixer.Info mixerInfo) -> score(mixerInfo, want)).reversed());
        return supported;
    }

    private static int score(Mixer.Info mixerInfo, String preferredMixer) {
        if (!preferredMixer.isEmpty() && mixerInfo.getName().equals(preferredMixer)) {
            return 300;
        }
        String name = mixerInfo.getName().toLowerCase(Locale.ROOT);
        String description = mixerInfo.getDescription().toLowerCase(Locale.ROOT);
        if (name.contains("default") || name.contains("pulse") || name.contains("pipewire")
                || description.contains("pulse") || description.contains("pipewire")) {
            return 200;
        }
        if (isRawHardware(mixerInfo)) {
            return 100;
        }
        return 150;
    }

    /** True for mixers that bypass the sound server (Linux ALSA {@code plughw}/{@code hw} and friends). */
    private static boolean isRawHardware(Mixer.Info mixerInfo) {
        String name = mixerInfo.getName().toLowerCase(Locale.ROOT);
        return name.contains("plughw") || name.contains("hw:") || name.contains("front:")
                || name.contains("surround") || name.contains("hdmi") || name.contains("iec958")
                || name.contains("spdif");
    }

    private static String mixerNames() {
        StringBuilder names = new StringBuilder();
        for (Mixer.Info available : AudioSystem.getMixerInfo()) {
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append('\'').append(available.getName()).append('\'');
        }
        return names.toString();
    }

    @Override
    public void write(byte[] chunk) {
        float v = this.volume;
        if (v < 0.999f) {
            AudioOutput.scale(chunk, v);
        }
        this.line.write(chunk, 0, chunk.length);
    }

    @Override
    public long mediaPositionUs() {
        return this.line.getMicrosecondPosition();
    }

    @Override
    public void start() {
        this.line.start();
    }

    @Override
    public void setVolume(float volume) {
        this.volume = Math.max(0.0f, Math.min(1.0f, volume));
    }

    @Override
    public void stop() {
        this.line.stop();
    }

    @Override
    public void flush() {
        this.line.stop();
        this.line.flush();
        this.line.start();
    }

    @Override
    public void close() {
        try {
            this.line.stop();
            this.line.close();
        } catch (Throwable t) {
            LOGGER.debug("Closing the audio output failed", t);
        }
    }
}
