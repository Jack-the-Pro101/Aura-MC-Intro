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
 * <p>The player writes each chunk when the wall clock reaches its timestamp, so the time from a
 * write to the ear - the latency the server reports right after it - is exactly how far the sound
 * trails its timestamp: the server buffer plus the sink's own delay (a Bluetooth codec and
 * headset add a few hundred milliseconds). That measured figure is what the picture is delayed by
 * ({@link #nominalLatencyMs()}). A report that is implausibly small - PipeWire's PulseAudio layer
 * can report 0 around underruns - is ignored.</p>
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
    /** Guards the native stream: every libpulse call and the handle's release. */
    private final Object streamLock = new Object();
    private volatile Pointer handle;
    private final int rate;
    private final int channels;
    private final boolean debug;
    private final IntByReference error = new IntByReference();
    private volatile float volume;
    private volatile long writtenBytes;
    private boolean writeErrorLogged;
    private byte[] silence;

    // Write-to-ear latency, measured after every write (see measureLatency). Starts at the
    // buffer target, the floor it can never be below once the stream plays.
    private double latencyEmaUs = TARGET_BUFFER_US;
    private volatile int publishedLatencyMs = (int) (TARGET_BUFFER_US / 1000L);

    // Diagnostic playback position (mediaPositionUs): advances at most with wall time and never
    // backwards, so a report that is briefly off cannot leap it. Guarded by `this`, which no
    // blocking call ever holds - the client thread's pause/resume must not wait on a write.
    private long playedUs;
    private long playedStampNanos;
    private long pausedSinceNanos;

    private PulseAudioOutput(PulseSimple pulse, Pointer handle, int rate, int channels, float volume,
                             boolean debug) {
        this.pulse = pulse;
        this.handle = handle;
        this.rate = rate;
        this.channels = channels;
        this.volume = volume;
        this.debug = debug;
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
        return new PulseAudioOutput(pulse, handle, rate, channels, volume, debug);
    }

    private static Pointer tryOpen(PulseSimple pulse, int rate, int channels, String device,
                                   IntByReference error) {
        PaSampleSpec spec = new PaSampleSpec();
        spec.format = PA_SAMPLE_S16LE;
        spec.rate = rate;
        spec.channels = (byte) channels;
        PaBufferAttr attr = new PaBufferAttr();
        // pa_buffer_attr is in BYTES. (It used to be filled with the microsecond figure, which at
        // 48 kHz stereo is ~1 s of audio: once prebuf stopped the server from skipping ahead,
        // that whole second could sit in the server and the sound trailed the picture by it.)
        //
        // Cap = target = TARGET_BUFFER_US: writes block once that much is buffered, so the depth
        // the picture is delayed by (nominalLatencyMs) is the depth the sound really has, and
        // anything a stalled sink would let pile up beyond it stays in the player's queue, where
        // the stale-drop can still throw it away instead of playing it late.
        int target = bytesFor(rate, channels, TARGET_BUFFER_US);
        int chunk = bytesFor(rate, channels, 20_000L);
        attr.maxlength = target;
        attr.tlength = target;
        attr.minreq = chunk;
        // Prebuf = the target minus one request (the largest prebuf the server accepts for this
        // tlength). The player writes each chunk when the wall clock reaches its timestamp, so
        // playback starts - and restarts after an underrun - only once the stream holds the
        // depth the picture is delayed by: picture and sound start together. Never 0: with
        // prebuf 0 the read index keeps running through an underrun and every later write lands
        // "in the past" and is skipped (measured: 0 of 3 s of tone audible after a 2 s idle gap).
        // The gate cannot deadlock - the player's writes are paced by the wall clock, not by
        // playback.
        attr.prebuf = target - chunk;
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

    private static int bytesFor(int rate, int channels, long micros) {
        return (int) (micros * rate / 1_000_000L) * channels * 2;
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

    /** {@code pa_buffer_attr}, values in bytes; -1 lets the server choose. */
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

    /** Ignore latency reports below this: the stream's own prebuf depth alone is more. */
    private static final long MIN_PLAUSIBLE_LATENCY_US = TARGET_BUFFER_US / 2L;
    private static final long MAX_PLAUSIBLE_LATENCY_US = 2_000_000L;
    /** Smoothing per 20 ms chunk - settles within about half a second. */
    private static final double LATENCY_EMA_ALPHA = 1.0 / 16.0;
    /** The picture's delay only follows a change larger than this, so it never jitters. */
    private static final int LATENCY_HYSTERESIS_MS = 15;

    @Override
    public int rate() {
        return this.rate;
    }

    @Override
    public int channels() {
        return this.channels;
    }

    @Override
    public void write(byte[] chunk) {
        float v = this.volume;
        if (v < 0.999f) {
            AudioOutput.scale(chunk, v);
        }
        if (!writeBlocking(chunk) && !this.writeErrorLogged && this.handle != null) {
            this.writeErrorLogged = true;
            LOGGER.warn("Writing to the sound server failed (libpulse error {}) - "
                    + "the video's sound stops here", this.error.getValue());
        }
    }

    @Override
    public boolean idle() {
        // Keeps the stream playing while the player is paused (the preload park, the loading
        // hold): a stream that sits in an underrun lets the sound server suspend the sink after
        // a few seconds, and waking a Bluetooth sink again takes a second or two - the start of
        // the sound was lost to exactly that. Writing silence keeps it running at its target
        // depth (the write blocks once the buffer is full, which paces this loop), so the real
        // sound continues behind it at the same depth - and the latency is already measured by
        // the time it does.
        if (this.silence == null) {
            this.silence = new byte[bytesFor(this.rate, this.channels, 20_000L)];
        }
        return writeBlocking(this.silence);
    }

    /** Writes (blocking while the server buffer is full) and measures the resulting latency. */
    private boolean writeBlocking(byte[] data) {
        synchronized (this.streamLock) {
            Pointer stream = this.handle;
            if (stream == null || this.pulse.pa_simple_write(stream, data, data.length, this.error) < 0) {
                return false;
            }
            this.writtenBytes += data.length;
            measureLatency(stream, data.length);
        }
        return true;
    }

    /**
     * Right after a write, the reported latency is how long until the <em>last</em> byte just written
     * is heard; the chunk's first sample - the one at the chunk's timestamp, which the player writes
     * when the clock reaches it - plays one chunk earlier. That start-to-ear figure is how far the
     * sound trails the clock, and the picture's delay. Smoothed, and only republished on a real
     * change.
     */
    private void measureLatency(Pointer stream, int writtenLength) {
        long chunkUs = writtenLength * 1_000_000L / (this.rate * (long) this.channels * 2L);
        long latencyUs = this.pulse.pa_simple_get_latency(stream, this.error) - chunkUs;
        if (latencyUs < MIN_PLAUSIBLE_LATENCY_US || latencyUs > MAX_PLAUSIBLE_LATENCY_US) {
            return;
        }
        this.latencyEmaUs += (latencyUs - this.latencyEmaUs) * LATENCY_EMA_ALPHA;
        int emaMs = (int) Math.round(this.latencyEmaUs / 1000.0);
        int published = this.publishedLatencyMs;
        if (Math.abs(emaMs - published) > LATENCY_HYSTERESIS_MS) {
            this.publishedLatencyMs = emaMs;
            if (this.debug) {
                LOGGER.info("Sound output latency is {} ms - the picture is delayed to match", emaMs);
            }
        }
    }

    @Override
    public synchronized long mediaPositionUs() {
        long now = System.nanoTime();
        if (this.playedStampNanos == 0L) {
            this.playedStampNanos = now;
        }
        // Wall time since the last sample, capped: a starved writer does not play audio, so a long
        // gap must not be credited to the audible position in one step.
        long elapsedUs = Math.min((now - this.playedStampNanos) / 1_000L, 150_000L);
        long writtenUs = this.writtenBytes * 1_000_000L / (this.rate * (long) this.channels * 2L);
        long position = Math.max(0L, writtenUs - this.publishedLatencyMs * 1000L);
        position = Math.min(position, this.playedUs + elapsedUs);
        this.playedUs = Math.max(this.playedUs, position);
        this.playedStampNanos = now;
        return this.playedUs;
    }

    @Override
    public synchronized void start() {
        // pa_simple has no separate running state: playback is driven by the writes, and the
        // audio thread idles the stream by itself while the player is paused. Resuming only moves
        // the diagnostic clock's reference point past the pause.
        if (this.pausedSinceNanos != 0L && this.playedStampNanos != 0L) {
            this.playedStampNanos += System.nanoTime() - this.pausedSinceNanos;
        }
        this.pausedSinceNanos = 0L;
    }

    @Override
    public synchronized void stop() {
        // No cork/flush on pause: the audio thread keeps the stream fed with silence (idle()).
        if (this.pausedSinceNanos == 0L) {
            this.pausedSinceNanos = System.nanoTime();
        }
    }

    @Override
    public void flush() {
        synchronized (this.streamLock) {
            Pointer stream = this.handle;
            if (stream != null && this.writtenBytes > 0L) {
                this.pulse.pa_simple_flush(stream, this.error);
            }
            this.writtenBytes = 0L;
        }
        synchronized (this) {
            // The flushed bytes never played: the played position restarts with the written one.
            this.playedUs = 0L;
        }
    }

    @Override
    public void setVolume(float volume) {
        this.volume = Math.max(0.0f, Math.min(1.0f, volume));
    }

    @Override
    public int nominalLatencyMs() {
        return this.publishedLatencyMs;
    }

    @Override
    public boolean positionTrustworthy() {
        // The picture runs on wall time delayed by the measured latency; the sound is written to
        // that same clock. A device-position clock is not needed - nor reliable here.
        return false;
    }

    @Override
    public void close() {
        Pointer stream;
        synchronized (this.streamLock) {
            stream = this.handle;
            this.handle = null;
        }
        if (stream != null) {
            try {
                this.pulse.pa_simple_free(stream);
            } catch (Throwable t) {
                LOGGER.debug("Closing the sound server stream failed", t);
            }
        }
    }
}
