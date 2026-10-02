package net.bluegaria.auraintro.client.video;

import org.bytedeco.ffmpeg.avutil.LogCallback;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacpp.BytePointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * FFmpeg's log, routed into the game log.
 *
 * <p>FFmpeg's default log callback writes to stderr, which the launcher's console may show but
 * {@code latest.log} never does - so the one thing that explains a failing hardware decoder (libva
 * naming the driver it could not open, for instance) was missing from every log a player shared.</p>
 *
 * <p>A thread can also {@link #capture} everything FFmpeg says during one call, verbose lines
 * included, to report it together with that call's outcome.</p>
 *
 * <p>Uses the presets' {@link LogCallback}, which formats each line natively before calling into
 * Java: a raw {@code av_log_set_callback} hands Java a {@code va_list}, and passing that back to
 * {@code av_log_format_line} crashed the JVM.</p>
 */
final class FfmpegLog {

    private static final Logger LOGGER = LoggerFactory.getLogger("Aura-Intro/FFmpeg");
    private static final ThreadLocal<List<String>> CAPTURE = new ThreadLocal<>();

    /** The level the game log gets, whatever a capture raised FFmpeg's own level to meanwhile. */
    private static volatile int level = avutil.AV_LOG_ERROR;
    /** Captures in progress: FFmpeg's level stays raised to verbose while there is any. */
    private static final AtomicInteger CAPTURES = new AtomicInteger();

    /** Strongly referenced: FFmpeg holds only the native function pointer. */
    private static LogCallback callback;

    private FfmpegLog() {
    }

    /** Installs the callback. Called once, right after the libraries are loaded. */
    static synchronized void install() {
        if (callback != null) {
            return;
        }
        callback = new LogCallback() {
            @Override
            public void call(int lineLevel, BytePointer line) {
                // Runs on whatever thread called av_log, FFmpeg's own decoder threads included: it
                // must never throw back into native code.
                try {
                    handle(lineLevel, line);
                } catch (Throwable ignored) {
                    // Losing a log line is fine; unwinding through FFmpeg is not.
                }
            }
        };
        avutil.setLogCallback(callback);
        avutil.av_log_set_level(level);
    }

    /** The level FFmpeg logs into the game log at (AV_LOG_ERROR, or AV_LOG_VERBOSE with debug logging). */
    static void setLevel(int newLevel) {
        level = newLevel;
        if (CAPTURES.get() == 0) {
            avutil.av_log_set_level(newLevel);
        }
    }

    private static void handle(int lineLevel, BytePointer line) {
        List<String> capture = CAPTURE.get();
        if (capture == null && lineLevel > level) {
            return; // only let through because a capture on another thread raised the level
        }
        String text = line == null ? "" : line.getString().trim();
        if (text.isEmpty()) {
            return;
        }
        if (capture != null) {
            capture.add(text);
        } else if (lineLevel <= avutil.AV_LOG_ERROR) {
            LOGGER.warn(text);
        } else {
            LOGGER.info(text);
        }
    }

    /**
     * Runs {@code call} on this thread and collects what FFmpeg logs meanwhile (down to verbose,
     * whatever the configured level) into {@code lines}, instead of the game log.
     */
    static boolean capture(List<String> lines, BooleanSupplier call) {
        List<String> previous = CAPTURE.get();
        List<String> collected = new ArrayList<>();
        CAPTURE.set(collected);
        if (CAPTURES.getAndIncrement() == 0) {
            avutil.av_log_set_level(Math.max(level, avutil.AV_LOG_VERBOSE));
        }
        try {
            return call.getAsBoolean();
        } finally {
            if (CAPTURES.decrementAndGet() == 0) {
                avutil.av_log_set_level(level);
            }
            CAPTURE.set(previous);
            lines.addAll(collected);
        }
    }
}
