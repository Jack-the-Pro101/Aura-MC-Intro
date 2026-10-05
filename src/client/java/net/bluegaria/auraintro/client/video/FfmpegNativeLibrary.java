package net.bluegaria.auraintro.client.video;

import net.bluegaria.auraintro.client.compat.Platform;
import com.sun.jna.Library;
import com.sun.jna.NativeLibrary;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avformat;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.ffmpeg.global.swresample;
import org.bytedeco.ffmpeg.global.swscale;
import org.bytedeco.javacpp.Loader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    /**
     * The system's VA-API libraries, when they could be loaded. Held for the rest of the process so
     * JNA never closes them again - FFmpeg is bound to them from the moment it is loaded.
     */
    private static final List<NativeLibrary> SYSTEM_LIBVA = new ArrayList<>();

    /** {@code RTLD_NOW | RTLD_GLOBAL} from glibc's dlfcn.h. */
    private static final int RTLD_NOW_GLOBAL = 0x002 | 0x100;

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
                Path cache = Platform.configDir()
                        .resolve("aura-intro").resolve("javacpp-cache");
                System.setProperty("org.bytedeco.javacpp.cachedir", cache.toAbsolutePath().toString());
            }
            boolean systemLibva = preferSystemLibva();
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
                LOGGER.info("FFmpeg {} loaded from the libraries bundled with the mod{}",
                        avutil.av_version_info().getString(), systemLibva ? " (VA-API through the system's libva)" : "");
            } catch (Throwable t) {
                LOGGER.warn("The bundled FFmpeg libraries could not be loaded - the video intro "
                        + "is disabled (vanilla behaviour is kept)", t);
            }
            return available;
        }
    }

    /**
     * Loads the system's own libva before FFmpeg, so FFmpeg binds to it instead of the copy in the
     * bytedeco natives jar (which JavaCPP preloads for {@code avutil}).
     *
     * <p>That bundled libva is 2.14 (VA-API 1.14), built on a Debian-style system: it only looks for
     * drivers in {@code /usr/lib/x86_64-linux-gnu/dri}, and it only knows the driver entry points up to
     * {@code __vaDriverInit_1_14}, while current drivers (Mesa, intel-media-driver) export just the one
     * of the libva they were built against ({@code __vaDriverInit_1_24} for Mesa 26). So VAAPI never
     * came up on Arch (drivers live in {@code /usr/lib/dri}) or any other up-to-date system - an Intel
     * laptop whose {@code vainfo} listed VP9 fell back to stuttering software decoding with
     * "va_openDriver() returns -1". The system's libva always matches the system's drivers.</p>
     *
     * <p>The minimal FFmpeg builds do not link libva at all (see {@code build-minimal.sh}): their
     * {@code va*} symbols stay undefined and bind lazily against the libraries loaded here with
     * {@code RTLD_GLOBAL}, and the jar carries no libva. Without a system libva, calling into VAAPI
     * would therefore end the process with a symbol lookup error - {@link #hasSystemLibva()} gates
     * every VAAPI attempt. FFmpeg builds that do link libva (bytedeco's, older minimal builds) come
     * with the bundled copy; the dynamic linker still resolves their {@code libva.so.2} dependency
     * against the library already loaded here, so they use the system's libva too.</p>
     *
     * @return whether the system's libva is loaded
     */
    private static boolean preferSystemLibva() {
        if (!Loader.getPlatform().startsWith("linux")) {
            return false;
        }
        // libva-drm depends on libva, so libva first; both or neither.
        List<NativeLibrary> loaded = new ArrayList<>();
        for (String name : new String[]{"libva.so.2", "libva-drm.so.2"}) {
            try {
                loaded.add(NativeLibrary.getInstance(name, Map.of(Library.OPTION_OPEN_FLAGS, RTLD_NOW_GLOBAL)));
            } catch (Throwable t) {
                LOGGER.debug("No system {} - FFmpeg uses the libva bundled with the mod", name, t);
                // A system libva without its DRM part would pair with the bundled libva-drm.
                loaded.forEach(NativeLibrary::close);
                return false;
            }
        }
        SYSTEM_LIBVA.addAll(loaded);
        return true;
    }

    /**
     * Whether the system's libva is loaded - the precondition for trying VAAPI at all (FFmpeg's
     * VAAPI code has nothing else to bind to, see {@link #preferSystemLibva()}).
     */
    public static boolean hasSystemLibva() {
        synchronized (LOCK) {
            return !SYSTEM_LIBVA.isEmpty();
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