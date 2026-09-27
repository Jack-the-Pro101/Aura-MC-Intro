package net.bluegaria.titlescreen.client.video;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Finds libVLC and makes its native libraries (and those its plugins need) loadable - once per process.
 *
 * <p>The loading screen background video is supposed to show its first frame the moment the window
 * appears, but loading libVLC takes about a second. {@link #warmUp(String)} is therefore called from the
 * pre-launch entrypoint, before the game's own main; everything here is cached, so the preparation during
 * the actual video preload is a no-op afterwards.</p>
 *
 * <p>Discovery order: the configured folder, a mod-local drop-in folder, the usual native locations and
 * finally the paths a Flatpak sandbox exposes the host under. The first directory that really contains
 * libvlc is registered through {@code jna.library.path} (which vlcj's native discovery honours) together
 * with its plugins through {@code VLC_PLUGIN_PATH}, and the libraries its plugins open by name are loaded
 * by absolute path - which is what removes the need for a launcher wrapper or an environment variable.</p>
 */
public final class VlcNativeLibrary {

    private static final Logger LOGGER = LoggerFactory.getLogger("Titlescreen/Video");

    /** Guards the one-time discovery; the result is cached for the rest of the process. */
    private static final Object LOCK = new Object();
    private static boolean checked;
    private static boolean available;

    private VlcNativeLibrary() {
    }

    /**
     * Loads libVLC's native libraries as early as possible - called before the game window exists, so the
     * first frame of the loading video can be ready when it appears.
     */
    public static void warmUp(String configuredPath) {
        prepare(configuredPath);
    }

    /**
     * Makes libVLC available, resolving and preparing it on the first call.
     *
     * @param configuredPath directory from the config, may be {@code null}/empty for auto-detection
     * @return {@code true} when libVLC can be used
     */
    public static boolean prepare(String configuredPath) {
        synchronized (LOCK) {
            if (checked) {
                return available;
            }
            checked = true;
            for (Path candidate : libVlcCandidates(configuredPath)) {
                if (!Files.isDirectory(candidate) || !containsLibVlc(candidate)) {
                    continue;
                }
                applyDirectory(candidate);
                LOGGER.info("Using libvlc from {}", candidate);
                available = true;
                return true;
            }
            if (new NativeDiscovery().discover()) {
                available = true;
                return true;
            }
            return false;
        }
    }

    /** Candidate directories for libvlc, in priority order. */
    private static java.util.List<Path> libVlcCandidates(String configuredPath) {
        java.util.List<Path> candidates = new java.util.ArrayList<>();
        if (configuredPath != null && !configuredPath.isBlank()) {
            candidates.add(Path.of(configuredPath.trim()));
        }
        candidates.add(FabricLoader.getInstance().getConfigDir().resolve("titlescreen/libvlc"));
        // Native installs first, in the order vlcj itself probes them.
        candidates.add(Path.of("/usr/lib64"));
        candidates.add(Path.of("/usr/lib/x86_64-linux-gnu"));
        candidates.add(Path.of("/usr/local/lib64"));
        candidates.add(Path.of("/usr/lib"));
        candidates.add(Path.of("/usr/local/lib"));
        // Sandboxed launchers (Flatpak/Snap) expose the host operating system under /run/host.
        candidates.add(Path.of("/run/host/usr/lib64"));
        candidates.add(Path.of("/run/host/usr/lib/x86_64-linux-gnu"));
        candidates.add(Path.of("/run/host/iso/usr/lib64"));
        return candidates;
    }

    private static boolean containsLibVlc(Path directory) {
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            return files.anyMatch(path -> {
                String name = path.getFileName().toString();
                return name.startsWith("libvlc.so") || name.startsWith("libvlc.dylib")
                        || name.equals("libvlc.dll") || name.startsWith("libvlc.");
            });
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Loads libvlccore directly from the chosen directory before JNA loads libvlc.
     *
     * <p>Custom libVLC locations are frequently outside the dynamic loader's search path - for example
     * {@code /run/host/usr/lib64} inside a Flatpak sandbox, where the host libraries are visible but the
     * loader does not look there. Loading libvlccore here registers its soname in the process, so
     * libvlc's own dependency resolves when JNA opens it.</p>
     */
    private static void preloadVlcCore(Path directory) {
        String[] prefixes = {"libvlccore.so", "libvlccore.dylib", "libvlccore"};
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            Path core = files
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        for (String prefix : prefixes) {
                            if (name.startsWith(prefix)) {
                                return true;
                            }
                        }
                        return false;
                    })
                    .sorted()
                    .findFirst()
                    .orElse(null);
            if (core == null) {
                return;
            }
            try {
                System.load(core.toAbsolutePath().toString());
                LOGGER.info("Preloaded {} so libvlc can resolve its dependencies", core);
            } catch (Throwable t) {
                LOGGER.debug("Could not preload {} (most likely already loaded)", core, t);
            }
        } catch (IOException e) {
            LOGGER.debug("Could not scan {} for libvlccore", directory, e);
        }
    }

    private static void applyDirectory(Path directory) {
        boolean preloaded = preloadPluginDependencies(directory);
        warnIfLoaderPathMissing(directory, preloaded);
        String existing = System.getProperty("jna.library.path", "");
        String prefix = directory + File.pathSeparator;
        if (!existing.startsWith(prefix)) {
            System.setProperty("jna.library.path", prefix + existing);
        }
        for (String candidate : new String[]{"plugins", "vlc/plugins", "lib/vlc/plugins"}) {
            Path plugins = directory.resolve(candidate);
            if (Files.isDirectory(plugins)) {
                String current = System.getProperty("VLC_PLUGIN_PATH", "");
                if (!current.contains(plugins.toString())) {
                    System.setProperty("VLC_PLUGIN_PATH",
                            current.isEmpty() ? plugins.toString() : plugins + File.pathSeparator + current);
                }
                LOGGER.info("Using libVLC plugins from {}", plugins);
                break;
            }
        }
        preloadVlcCore(directory);
    }

    /**
     * Library names that libVLC's plugins open by name. They are the ones that are missing when libVLC
     * itself sits outside the dynamic loader's search path (a Flatpak sandbox, a drop-in folder, ...).
     */
    private static final String[] PLUGIN_DEPENDENCY_PREFIXES = {
            "libvlc.so", "libvlccore.so",
            // demuxers/decoders and their conversion chain
            "libavformat.so", "libavcodec.so", "libavutil.so", "libswscale.so", "libswresample.so",
            "libpostproc.so", "libmatroska.so", "libebml.so", "libvpx.so", "libdav1d.so", "libaom.so",
            "libopus.so", "libvorbis.so", "libogg.so", "libzstd.so", "libbluray.so",
            // PulseAudio's private helper library: Fedora keeps it in its own subdirectory, which is not
            // on the loader path, so VLC's audio output module cannot find it. "libpulse.so" itself is
            // deliberately *not* loaded here: the game process already has the sandbox's own libpulse
            // (Minecraft's OpenAL uses it), and a second copy in the same process is asking for trouble.
            "libpulsecommon"
    };

    /** Directories that may hold the PulseAudio helper library inside a sandbox. */
    private static final String[] PULSE_AUDIO_DIRECTORIES = {
            "/usr/lib/x86_64-linux-gnu/pulseaudio",
            "/usr/lib64/pulseaudio",
            "/usr/lib/pulseaudio"
    };

    /**
     * Loads the libraries libVLC's plugins need directly from the libVLC directory, by absolute path.
     *
     * <p>libVLC finds its plugins without help, but the plugins themselves {@code dlopen} their
     * dependencies (libavformat, libmatroska, libpulsecommon, ...) by name, and the dynamic loader only
     * searches the default directories for those. Loading them here first registers their sonames in
     * the process, so the loader reuses them - which removes the need for a launcher wrapper script,
     * an environment variable or any other setup step.</p>
     *
     * @return {@code true} when at least one library was preloaded
     */
    private static boolean preloadPluginDependencies(Path directory) {
        List<Path> candidates = new ArrayList<>();
        List<Path> scanDirs = new ArrayList<>(List.of(directory, directory.resolve("vlc"),
                directory.resolve("pulseaudio")));
        // The sandbox's own PulseAudio helper library is preferred over a host copy.
        for (String extra : PULSE_AUDIO_DIRECTORIES) {
            scanDirs.add(Path.of(extra));
        }
        for (Path dir : scanDirs) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(Files::isRegularFile)
                        .filter(file -> matchesAny(file.getFileName().toString(), PLUGIN_DEPENDENCY_PREFIXES))
                        .filter(file -> {
                            // Only add the sandbox's libpulsecommon, never a second copy of one that is
                            // already visible to the loader (see PLUGIN_DEPENDENCY_PREFIXES).
                            String name = file.getFileName().toString();
                            return !name.startsWith("libpulsecommon") || !candidates.stream()
                                    .anyMatch(existing -> existing.getFileName().toString().startsWith("libpulsecommon"));
                        })
                        .sorted()
                        .forEach(candidates::add);
            } catch (IOException e) {
                LOGGER.debug("Could not scan {} for libVLC plugin dependencies", dir, e);
            }
        }
        if (candidates.isEmpty()) {
            return false;
        }

        // A library whose own dependencies are not loaded yet cannot be loaded; retry until a pass
        // makes no more progress (libavformat -> libavcodec -> libavutil, for example).
        boolean anyLoaded = false;
        List<Path> pending = new ArrayList<>(candidates);
        for (int pass = 0; pass < 4 && !pending.isEmpty(); pass++) {
            int before = pending.size();
            pending.removeIf(library -> {
                try {
                    System.load(library.toAbsolutePath().toString());
                    LOGGER.debug("Preloaded {}", library.getFileName());
                    return true;
                } catch (Throwable t) {
                    return false;
                }
            });
            anyLoaded |= pending.size() < before;
            if (pending.size() == before) {
                break;
            }
        }
        if (anyLoaded) {
            LOGGER.info("Preloaded {} libVLC plugin dependencies from {} (no launcher wrapper needed)",
                    candidates.size() - pending.size(), directory);
        }
        return anyLoaded;
    }

    private static boolean matchesAny(String name, String[] prefixes) {
        for (String prefix : prefixes) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * VLC resolves its plugin dependencies (libavformat, libvlc_pulse, ...) through the dynamic loader,
     * which does not search {@code jna.library.path}. Outside a sandbox the system directories are
     * already covered, but a sandboxed launcher needs the directory on {@code LD_LIBRARY_PATH} -
     * warn with the exact variable so users can fix it in one step.
     */
    private static void warnIfLoaderPathMissing(Path directory, boolean pluginDependenciesPreloaded) {
        if (pluginDependenciesPreloaded) {
            // The mod already loaded everything the plugins need - nothing for the user to do.
            return;
        }
        String ldLibraryPath = System.getenv("LD_LIBRARY_PATH");
        if (ldLibraryPath != null && ldLibraryPath.contains(directory.toString())) {
            return;
        }
        if (loaderPathProvidesVlcCore(ldLibraryPath)) {
            // A symlink farm (a directory of links to the host libraries) works just as well: the
            // loader finds libvlccore and therefore also the plugins' dependencies.
            return;
        }
        boolean sandboxed = Files.exists(Path.of("/.flatpak-info")) || directory.startsWith("/run/host");
        if (!sandboxed) {
            return;
        }
        LOGGER.warn("VLC's plugins need their dependencies on the library search path; if the videos stay "
                + "black, put this on the *instance*'s LD_LIBRARY_PATH (PrismLauncher: Edit -> Settings -> "
                + "Environment variables, override global): {}", suggestedLoaderPath(directory));
        LOGGER.warn("Keep the GL extension directories first: without them the host's GL/driver libraries "
                + "shadow the sandbox's (LD_LIBRARY_PATH outranks the loader cache) and Sodium hangs. Also "
                + "set ALSOFT_DRIVERS=pulse,alsa, otherwise Minecraft's own OpenAL device fails to open.");
        LOGGER.warn("Do not set these via 'flatpak override --env', that replaces the launcher's own "
                + "environment and breaks the launcher (e.g. dlopen of libopenal.so fails).");
    }

    /**
     * The library path a sandboxed user should set: the sandbox's GL extension directories (see the note
     * about Sodium in {@link #warnIfLoaderPathMissing}), then the libVLC directory and the subdirectories
     * that hold VLC's module libraries and PulseAudio's {@code libpulsecommon}.
     */
    private static String suggestedLoaderPath(Path directory) {
        List<String> entries = new ArrayList<>();
        entries.add("/usr/lib/x86_64-linux-gnu/GL/default/lib");
        Path glRoot = Path.of("/usr/lib/x86_64-linux-gnu/GL");
        if (Files.isDirectory(glRoot)) {
            try (Stream<Path> children = Files.list(glRoot)) {
                children.filter(Files::isDirectory)
                        .map(child -> child.getFileName().toString())
                        .filter(name -> name.startsWith("nvidia-") || name.startsWith("intel-"))
                        .sorted()
                        .forEach(name -> entries.add(glRoot.resolve(name).resolve("lib").toString()));
            } catch (IOException ignored) {
                // Falling back to the default GL directory is fine.
            }
        }
        entries.add(directory.toString());
        for (String extra : new String[]{"vlc", "samba", "pulseaudio"}) {
            Path candidate = directory.resolve(extra);
            if (Files.isDirectory(candidate)) {
                entries.add(candidate.toString());
            }
        }
        return String.join(File.pathSeparator, entries);
    }

    /**
     * True when any {@code LD_LIBRARY_PATH} entry already contains libVLC's core library, which means
     * the plugins' dependencies resolve through the dynamic loader (see {@link #warnIfLoaderPathMissing}).
     */
    private static boolean loaderPathProvidesVlcCore(String ldLibraryPath) {
        if (ldLibraryPath == null || ldLibraryPath.isEmpty()) {
            return false;
        }
        for (String entry : ldLibraryPath.split(File.pathSeparator)) {
            if (entry.isEmpty()) {
                continue;
            }
            try (Stream<Path> files = Files.list(Path.of(entry))) {
                if (files.anyMatch(file -> file.getFileName().toString().startsWith("libvlccore.so"))) {
                    return true;
                }
            } catch (IOException | InvalidPathException ignored) {
                // Unreadable entries simply do not count.
            }
        }
        return false;
    }

    /** Actionable hint when the game runs inside a sandbox that cannot see the host's libvlc. */
    public static String sandboxHint() {
        Path flatpakInfo = Path.of("/.flatpak-info");
        if (!Files.exists(flatpakInfo)) {
            return null;
        }
        String appId = "this application";
        try {
            for (String line : Files.readAllLines(flatpakInfo)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("name=")) {
                    appId = trimmed.substring("name=".length()).trim();
                    break;
                }
            }
        } catch (IOException ignored) {
            // Keep the generic app id.
        }
        return "This game appears to run inside a Flatpak sandbox, which cannot see the host's VLC "
                + "libraries. Expose them with: flatpak override --user --filesystem=host-os " + appId
                + "  (or, for just the libraries: --filesystem=/usr/lib64:ro, use "
                + "/usr/lib/x86_64-linux-gnu on Debian/Ubuntu based hosts).";
    }
}
