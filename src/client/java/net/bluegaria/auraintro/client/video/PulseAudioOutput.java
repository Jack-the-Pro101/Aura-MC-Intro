package net.bluegaria.auraintro.client.video;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plays the video's PCM through the system sound server (PulseAudio, or PipeWire's compatibility
 * layer) with libpulse's simple API - the same client library, socket and routing the game's own
 * OpenAL sound uses when it runs on {@code pulse}.
 *
 * <p>That is the point: whatever the desktop considers its default output (headphones vs.
 * speakers), the game and the video end up on the same one, switching together with the desktop
 * setting. It is also the only route that works from inside sandboxed launchers (Flatpak/Snap):
 * there the sandbox shares the sound server's socket, but ALSA - which Java Sound speaks - has no
 * sound server behind it, so its devices are either missing (the "default" device) or raw hardware
 * bound to one specific jack.</p>
 *
 * <p>{@code pa_simple_write} blocks while the server-side buffer is full, which paces the feed
 * exactly like a blocking {@code SourceDataLine.write}. Its reported latency is another matter:
 * on PipeWire's PulseAudio layer it collapses to a permanent 0 after the first underrun, so
 * {@link #mediaPositionUs()} is only a diagnostic estimate there and the player runs the picture
 * on wall time (see {@link #positionTrustworthy()}).</p>
 */
final class PulseAudioOutput implements AudioOutput {

    private static final Logger LOGGER = LoggerFactory.getLogger("Aura-Intro/Video");

    /** libpulse-simple is only ever shipped versioned (no dev symlink) - try all spellings. */
    private static final String[] LIBRARY_NAMES = {"pulse-simple", "pulse-simple.so.0", "libpulse-simple.so.0"};

    /** pa_stream_direction_t */
    private static final int PA_STREAM_PLAYBACK = 1;
    /** pa_sample_format_t: signed 16-bit little endian, the format the resampler emits. */
    private static final int PA_SAMPLE_S16LE = 3;
    /**
     * The stream's server-side depth (see {@link #tryOpen}). Also the honest minimum of its
     * latency: everything written spends at least this long in the pipeline before it is heard.
     */
    private static final long TARGET_BUFFER_US = 200_000L;

    private static volatile PulseSimple library;
    private static volatile boolean libraryMissing;

    private final PulseSimple pulse;
    private volatile Pointer handle;
    private final int rate;
    private final int channels;
    private volatile float volume;
    private long writtenBytes;
    private long lastLatencyUs;
    private boolean writeErrorLogged;

    // The playback clock. get_latency is the honest audible position while the server reports it,
    // but on PipeWire's PulseAudio layer it turns into a permanent 0 after the first underrun -
    // deriving the clock from it alone then makes "written - 0" race at write pace, which the
    // picture chases in bursts (video freezing, catching up, freezing again). The audible position
    // therefore advances at most with wall time and at most to what was written, with the latency
    // report used whenever it is plausible.
    private long playedUs;
    private long playedStampNanos;
    private long pausedSinceNanos;

    private PulseAudioOutput(PulseSimple pulse, Pointer handle, int rate, int channels, float volume) {
        this.pulse = pulse;
        this.handle = handle;
        this.rate = rate;
        this.channels = channels;
        this.volume = volume;
    }

    /**
     * Opens a stream through the sound server, if libpulse is available and a connection can be
     * made. {@code preferredDevice} names a PulseAudio sink (as shown by the desktop's audio
     * widget); empty means the default output. Returns {@code null} for the caller to fall back
     * to Java Sound - never throws.
     */
    static PulseAudioOutput open(Object context, int rate, int channels, float volume,
                                 boolean debug, String preferredDevice) {
        PulseSimple pulse = library();
        if (pulse == null) {
            return null; // no libpulse on this system - the Java Sound path takes over
        }
        String device = preferredDevice == null ? "" : preferredDevice.trim();
        IntByReference error = new IntByReference();
        Pointer handle = null;
        String openedOn = "default";
        if (!device.isEmpty()) {
            handle = tryOpen(pulse, rate, channels, device, error);
            if (handle != null) {
                openedOn = device;
            }
        }
        if (handle == null) {
            handle = tryOpen(pulse, rate, channels, null, error);
        }
        if (handle == null) {
            LOGGER.warn("The system sound server is not reachable for {} (libpulse error {}) - "
                    + "falling back to Java Sound", context, error.getValue());
            return null;
        }
        if (debug) {
            LOGGER.info("Opened a {} Hz x {} channel audio output through the system sound server "
                            + "(sink: {}) for {}", rate, channels, openedOn, context);
        }
        return new PulseAudioOutput(pulse, handle, rate, channels, volume);
    }

    private static Pointer tryOpen(PulseSimple pulse, int rate, int channels, String device,
                                   IntByReference error) {
        PaSampleSpec spec = new PaSampleSpec();
        spec.format = PA_SAMPLE_S16LE;
        spec.rate = rate;
        spec.channels = (byte) channels;
        PaBufferAttr attr = new PaBufferAttr();
        // Cap = target: the buffered depth then stays where it settled, so the latency remembered
        // for the clock (see mediaPositionUs) equals the steady depth instead of a fill peak that
        // would leave the picture lagging the sound by the difference.
        attr.maxlength = (int) TARGET_BUFFER_US; // ~200 ms, and a hard cap: without one writes never block and a
                                                 // clock-paced decoder cannot be paced by its own audio queue
        attr.tlength = (int) TARGET_BUFFER_US;   // ~200 ms target buffer - stall headroom at a small clock lag
        attr.prebuf = 0;          // start playing with the first bytes: a prebuf gate re-arms after every
                                  // flush/underrun and deadlocks against a clock-paced decoder that only
                                  // produces more audio once playback has started
        try {
            // The client name groups the video's stream with the game's own sound in the desktop's
            // volume mixer, so per-application routing set for the game applies to the video too.
            return pulse.pa_simple_new(null, "Minecraft", PA_STREAM_PLAYBACK, device,
                    "Aura-Intro video", spec, null, attr, error);
        } catch (Throwable t) {
            LOGGER.debug("Opening the sound server stream failed", t);
            return null;
        }
    }

    private static PulseSimple library() {
        PulseSimple pulse = library;
        if (pulse != null || libraryMissing) {
            return pulse;
        }
        synchronized (PulseAudioOutput.class) {
            if (library == null && !libraryMissing) {
                for (String name : LIBRARY_NAMES) {
                    try {
                        library = Native.load(name, PulseSimple.class);
                        break;
                    } catch (Throwable ignored) {
                        // Try the next spelling; runtimes ship only the versioned file.
                    }
                }
                if (library == null) {
                    libraryMissing = true;
                }
            }
            return library;
        }
    }

    // ------------------------------------------------------------------
    // libpulse-simple bindings (JNA)
    // ------------------------------------------------------------------

    private interface PulseSimple extends Library {
        Pointer pa_simple_new(String server, String name, int direction, String device, String streamName,
                              PaSampleSpec spec, Pointer channelMap, PaBufferAttr attr, IntByReference error);

        int pa_simple_write(Pointer simple, byte[] data, long bytes, IntByReference error);

        /** Playback latency in microseconds (everything buffered between write and the speaker). */
        long pa_simple_get_latency(Pointer simple, IntByReference error);

        /** Drops what is still buffered (both a seek and an immediate pause). */
        int pa_simple_flush(Pointer simple, IntByReference error);

        void pa_simple_free(Pointer simple);
    }

    /** {@code pa_sample_spec}: one of the few plain structures the simple API needs. */
    @Structure.FieldOrder({"format", "rate", "channels"})
    public static class PaSampleSpec extends Structure {
        public int format;
        public int rate;
        public byte channels;
    }

    /** {@code pa_buffer_attr}, values in microseconds; -1 lets the server choose. */
    @Structure.FieldOrder({"maxlength", "tlength", "prebuf", "minreq", "fragsize"})
    public static class PaBufferAttr extends Structure {
        public int maxlength = -1;
        public int tlength = -1;
        public int prebuf = -1;
        public int minreq = -1;
        public int fragsize = -1;
    }

    // ------------------------------------------------------------------
    // AudioOutput
    // ------------------------------------------------------------------

    private static final IntByReference ERROR = new IntByReference();

    @Override
    public int rate() {
        return this.rate;
    }

    @Override
    public int channels() {
        return this.channels;
    }

    @Override
    public synchronized void write(byte[] chunk) {
        Pointer stream = this.handle;
        if (stream == null) {
            return;
        }
        float v = this.volume;
        if (v < 0.999f) {
            AudioOutput.scale(chunk, v);
        }
        if (this.pulse.pa_simple_write(stream, chunk, chunk.length, ERROR) < 0) {
            if (!this.writeErrorLogged) {
                this.writeErrorLogged = true;
                LOGGER.warn("Writing to the sound server failed (libpulse error {}) - "
                        + "the video's sound stops here", ERROR.getValue());
            }
            return;
        }
        this.writtenBytes += chunk.length;
    }

    @Override
    public synchronized long mediaPositionUs() {
        Pointer stream = this.handle;
        if (stream == null) {
            return this.playedUs;
        }
        long now = System.nanoTime();
        if (this.playedStampNanos == 0L) {
            this.playedStampNanos = now;
        }
        // Wall time since the last sample, capped: silence (a starved writer, a thread that was
        // not scheduled) does not play audio, so a long gap must not be credited to the audible
        // position in one step - that leap is what the picture chases in a sped-up burst.
        long elapsedUs = Math.min((now - this.playedStampNanos) / 1_000L, 150_000L);
        long writtenUs = this.writtenBytes * 1_000_000L / (this.rate * (long) this.channels * 2L);
        long position;
        long latencyUs = this.pulse.pa_simple_get_latency(stream, ERROR);
        if (latencyUs >= 0L) {
            long reportedUs = Math.max(0L, writtenUs - latencyUs);
            if (reportedUs >= this.playedUs - 100_000L
                    && reportedUs <= this.playedUs + elapsedUs + 100_000L) {
                // An honest report - which includes everything between the write and the ear
                // (server buffer *and* the output device's own latency, e.g. Bluetooth). Use it
                // and remember the depth it implies, because...
                this.lastLatencyUs = latencyUs;
                position = reportedUs;
            } else {
                // ...on PipeWire's PulseAudio layer the report dies to a permanent 0 after the
                // first underrun, and "written - 0" leads the audible sound by the whole buffered
                // depth. The remembered depth keeps the clock glued to what is actually heard
                // instead of racing the written position.
                position = Math.max(0L, writtenUs - this.lastLatencyUs);
            }
        } else {
            position = Math.max(0L, writtenUs - this.lastLatencyUs);
        }
        // Real-time playback: never advance faster than wall time either way.
        position = Math.min(position, this.playedUs + elapsedUs);
        this.playedUs = Math.max(this.playedUs, position);
        this.playedStampNanos = now;
        return this.playedUs;
    }

    @Override
    public synchronized void start() {
        // pa_simple has no separate running state: playback is driven by the writes, and the
        // audio thread parks by itself while the player is paused. Resuming only moves the
        // clock's reference point past the pause, so no wall time accrues while paused.
        if (this.pausedSinceNanos != 0L && this.playedStampNanos != 0L) {
            this.playedStampNanos += System.nanoTime() - this.pausedSinceNanos;
        }
        this.pausedSinceNanos = 0L;
    }

    @Override
    public synchronized void stop() {
        // No cork/flush on pause: a flush here re-arms the server's prebuf state and deadlocks a
        // clock-paced decoder. The audio thread parking is the pause; this only marks where the
        // playback clock's wall-time term has to stop.
        if (this.pausedSinceNanos == 0L) {
            this.pausedSinceNanos = System.nanoTime();
        }
    }

    @Override
    public synchronized void flush() {
        Pointer stream = this.handle;
        if (stream != null && this.writtenBytes > 0L) {
            this.pulse.pa_simple_flush(stream, ERROR);
        }
        // The flushed bytes never played: both the written and the played position restart at
        // zero, or the clock would leap forward past everything ever written.
        this.writtenBytes = 0L;
        this.playedUs = 0L;
        this.lastLatencyUs = 0L;
    }

    @Override
    public void setVolume(float volume) {
        this.volume = Math.max(0.0f, Math.min(1.0f, volume));
    }

    @Override
    public int nominalLatencyMs() {
        // The configured server-side depth is the honest minimum; a live, plausible latency report
        // is better when the server gives one (it includes the server's own extras). After
        // PipeWire's post-underrun permanent-0 reports, the configured depth is all that is left.
        long us = Math.max(TARGET_BUFFER_US, this.lastLatencyUs);
        return (int) Math.min(1000L, us / 1000L);
    }

    @Override
    public boolean positionTrustworthy() {
        // get_latency on PipeWire's PulseAudio layer dies to a permanent 0 after the first
        // underrun; the wall-clamped estimate derived from it is decent for diagnostics but not
        // a master clock. The picture is paced by wall time instead; the sound follows as well
        // as the server plays it.
        return false;
    }

    @Override
    public synchronized void close() {
        Pointer stream = this.handle;
        this.handle = null;
        if (stream != null) {
            try {
                this.pulse.pa_simple_free(stream);
            } catch (Throwable t) {
                LOGGER.debug("Closing the sound server stream failed", t);
            }
        }
    }
}
