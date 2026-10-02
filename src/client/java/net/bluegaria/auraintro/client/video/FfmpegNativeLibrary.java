package net.bluegaria.auraintro.client.video;

import net.fabricmc.loader.api.FabricLoader;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avformat;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.ffmpeg.global.swresample;
import org.bytedeco.ffmpeg.global.swscale;
import org.bytedeco.javacpp.Loader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Loads the FFmpeg libraries bundled with the mod - once per process.
 *
 * <p>The mod ships prebuilt FFmpeg libraries for every supported platform inside its jar (JavaCPP
 * presets). On first use JavaCPP's loader extracts the libraries of the running platform into a
 * cache directory and loads them - no system installation, no launcher setup, and unlike the old
 * libVLC integration no discovery of host installations is needed at all.</p>
 *
 * <p>The loading screen background video is supposed to show its first frame the moment the window
 * appears, but extracting and loading the libraries takes a moment. {@link #warmUp()} is therefore
 * called from the pre-launch entrypoint, before the game's own main; everything here is cached, so
 * the preparation during the actual video preload is a no-op afterwards.</p>
 *
 * <p>The extracted libraries are kept next to the config ({@code config/aura-intro/javacpp-cache})
 * so they survive restarts. A directory set through {@code org.bytedeco.javacpp.cachedir} before
 * this class runs is respected and never overridden.</p>
 */
public final class FfmpegNativeLibrary {

    private static final Logger LOGGER = LoggerFactory.getLogger("Aura-Intro/Video");

    /** Guards the one-time preparation; the result is cached for the rest of the process. */
    private static final Object LOCK = new Object();
    private static boolean checked;
    private static boolean available;

    private FfmpegNativeLibrary() {
    }

    /**
     * Loads the bundled FFmpeg libraries as early as possible - called before the game window
     * exists, so the first frame of the loading video can be ready when it appears.
     */
    public static void warmUp() {
        prepare();
    }

    /**
     * Makes FFmpeg available, resolving and preparing it on the first call.
     *
     * @return {@code true} when the bundled libraries could be loaded
     */
    public static boolean prepare() {
        synchronized (LOCK) {
            if (checked) {
                return available;
            }
            checked = true;
            if (System.getProperty("org.bytedeco.javacpp.cachedir") == null) {
                Path cache = FabricLoader.getInstance().getConfigDir()
                        .resolve("aura-intro").resolve("javacpp-cache");
                System.setProperty("org.bytedeco.javacpp.cachedir", cache.toAbsolutePath().toString());
            }
            try {
                // Exactly the modules the video player uses - avdevice, avfilter and postproc
                // are stripped from the bundled jars and are never loaded.
                Loader.load(avutil.class);
                Loader.load(swresample.class);
                Loader.load(swscale.class);
                Loader.load(avcodec.class);
                Loader.load(avformat.class);
                FfmpegLog.install();
                available = true;
                LOGGER.info("FFmpeg {} loaded from the libraries bundled with the mod",
                        avutil.av_version_info().getString());
            } catch (Throwable t) {
                LOGGER.warn("The bundled FFmpeg libraries could not be loaded - the video intro "
                        + "is disabled (vanilla behaviour is kept)", t);
            }
            return available;
        }
    }

    /** Whether {@link #prepare()} has succeeded. */
    public static boolean isAvailable() {
        synchronized (LOCK) {
            prepare();
            return available;
        }
    }
}