package net.bluegaria.titlescreen.client.video;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.TrackDescription;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;
import uk.co.caprica.vlcj.player.embedded.videosurface.CallbackVideoSurface;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback;
import uk.co.caprica.vlcj.media.VideoTrackInfo;

import net.fabricmc.loader.api.FabricLoader;

import org.lwjgl.system.MemoryUtil;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * libVLC based {@link VideoPlayer} using vlcj's direct (callback) video output.
 *
 * <p>Requesting the {@code RGBA} chroma makes libVLC hand us frames with a real alpha channel,
 * which is what allows transparent WebM/VP9 videos to be composited over the title screen.
 * Audio is played by libVLC itself, so nothing has to be mixed into Minecraft's sound engine.</p>
 */
public final class VlcVideoPlayer implements VideoPlayer {

    private static final Logger LOGGER = LoggerFactory.getLogger("Titlescreen/Video");
    private static final String CHROMA = "RGBA";
    private static final String RV32_PREFIX = "RV32";

    private final VideoFrameSink sink = new VideoFrameSink();

    /** True while a background thread is tearing this player down - see {@link #close()}. */
    private final AtomicBoolean closing = new AtomicBoolean();

    /**
     * Serialises the native libVLC calls that create or destroy a player. Two players exist during the
     * hand-over from the loading screen to the title screen (the frozen background plus the intro), and
     * creating one while the other is being torn down inside JNA's marshalling is the kind of overlap
     * that produces a rare hard freeze. Creation only *tries* to take it (see {@code open}) so a
     * teardown that hangs inside libVLC cannot stop a new player from starting.
     */
    private static final ReentrantLock PLAYER_LOCK = new ReentrantLock();

    /** Guards the one-time native setup - see {@link #prepareNativeOnce(String)}. */
    private static final Object NATIVE_LOCK = new Object();
    private static boolean nativeChecked;
    private static boolean nativeAvailable;

    private MediaPlayerFactory factory;
    private EmbeddedMediaPlayer player;

    private volatile boolean finished;
    private volatile long lengthMs;
    /** Cached from libVLC's timeChanged event - see {@link #cachedTimeMs()}. */
    private volatile long cachedTimeMs = -1L;
    private volatile int pendingVolume;
    private volatile boolean debug;
    private volatile boolean hardwareDecoding = true;
    private volatile boolean preloaded;

    /**
     * All libVLC control calls (volume, mute, audio track, pause) run here, one at a time.
     *
     * <p>libVLC's audio functions block while its audio output is being created, and calling them from
     * inside a libVLC event callback - which is what the {@code playing} handler used to do - or from the
     * render thread at the same moment deadlocked the whole client: a thread dump showed both the
     * {@code media-player-events} thread and the render thread stuck in {@code libvlc_audio_set_volume}.
     * Serialising them on one thread, never on the game's own threads, removes both halves of that.</p>
     */
    private final ExecutorService commands = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Titlescreen-Video-Commands");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean pauseAfterFirstFrame;
    private volatile boolean firstFrameSeen;
    private boolean sourceSizeLogged;
    private int sourceWidth;
    private int sourceHeight;

    @Override
    public boolean preload(Path file, int volumePercent, String libVlcPath) {
        this.pauseAfterFirstFrame = true;
        boolean ok = open(file, volumePercent, libVlcPath);
        this.preloaded = ok;
        if (!ok) {
            this.pauseAfterFirstFrame = false;
            return false;
        }
        if (volumePercent <= 0) {
            // Only start re-asserting silence once the preload flag is set: the loop returns immediately
            // otherwise, which is why the preload was occasionally audible for its first moments.
            keepPreloadSilent();
        }
        return true;
    }

    @Override
    public boolean isPreloaded() {
        return this.preloaded;
    }

    @Override
    public void resumeFromPreload() {
        if (this.preloaded) {
            this.preloaded = false;
            this.pauseAfterFirstFrame = false;
            postCommand(() -> setAudioEnabled(this.player, true));
            setPaused(false);
        }
    }

    /** Called from the client thread once the first preload frame arrived. */
    @Override
    public boolean consumePauseAfterFirstFrame() {
        if (this.pauseAfterFirstFrame && this.firstFrameSeen) {
            this.pauseAfterFirstFrame = false;
            return true;
        }
        return false;
    }

    @Override
    public boolean start(Path file, int volumePercent, String libVlcPath) {
        return open(file, volumePercent, libVlcPath);
    }

    private synchronized boolean open(Path file, int volumePercent, String libVlcPath) {
        this.pendingVolume = volumePercent;
        try {
            if (!prepareNativeOnce(libVlcPath)) {
                String hint = sandboxHint();
                LOGGER.warn("libvlc could not be located. Install VLC or set 'libVLC folder' in the titlescreen "
                        + "config to a directory containing libvlc. The mod keeps working without it.");
                if (hint != null) {
                    LOGGER.warn(hint);
                }
                close();
                return false;
            }

            boolean locked = false;
            try {
                locked = PLAYER_LOCK.tryLock(2L, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (!locked) {
                // A previous player is stuck in libVLC's teardown - better to start this one without the
                // lock than to never start it at all.
                LOGGER.warn("libVLC is still tearing down another player - starting the video anyway");
            }
            try {
                this.factory = new MediaPlayerFactory(playerArguments(false));
                this.player = this.factory.mediaPlayers().newEmbeddedMediaPlayer();
                FormatCallback formatCallback = new FormatCallback();
                CallbackVideoSurface surface = this.factory.videoSurfaces()
                        .newVideoSurface(formatCallback, new FrameCallback(formatCallback), false);
                this.player.videoSurface().set(surface);
                this.player.events().addMediaPlayerEventListener(new PlayerEvents(file));

                if (volumePercent <= 0) {
                    // Neither the volume nor mute is honoured before libVLC has created its audio output,
                    // but the audio track switch is - so a preload that plays (and therefore decodes the
                    // frames the window opens onto) can still be silent.
                    setAudioEnabled(this.player, false);
                }
                if (!this.player.media().play(file.toAbsolutePath().toString())) {
                    LOGGER.warn("libvlc refused to play {}", file);
                    close();
                    return false;
                }
            } finally {
                if (locked) {
                    PLAYER_LOCK.unlock();
                }
            }
            postCommand(this::applyPendingVolume);
            return true;
        } catch (Throwable t) {
            LOGGER.warn("Failed to start video playback for {} - the video intro will be skipped", file, t);
            close();
            return false;
        }
    }

    /** One-shot debug dump of the padded buffer size libVLC hands out. */
    private void logFormatOnce(BufferFormat format, int bufferWidth, int bufferHeight) {
        if (!this.debug || this.sourceSizeLogged) {
            return;
        }
        this.sourceSizeLogged = true;
        LOGGER.info("libVLC video buffers: chroma={} buffer={}x{} (decoded frames are scaled by libVLC "
                        + "to this size; the true aspect ratio comes from the track size)",
                format.getChroma(), bufferWidth, bufferHeight);
    }

    /**
     * Switches the audio track on or off, so a player can run (and decode) silently.
     *
     * <p>Needed because the volume and mute settings are ignored until libVLC has created its audio output -
     * the reason the preload used to be started paused, which in turn meant no frame was decoded until the
     * loading screen resumed it. A track switch is honoured from the start.</p>
     */
    private static void setAudioEnabled(MediaPlayer mediaPlayer, boolean enabled) {
        if (mediaPlayer == null) {
            return;
        }
        try {
            if (enabled) {
                for (TrackDescription description : mediaPlayer.audio().trackDescriptions()) {
                    if (description.id() >= 0) {
                        mediaPlayer.audio().setTrack(description.id());
                        return;
                    }
                }
            } else {
                mediaPlayer.audio().setTrack(-1);
            }
        } catch (Throwable t) {
            LOGGER.debug("Could not switch the audio track (enabled={})", enabled, t);
        }
    }

    /** Runs a libVLC control call on the command thread, swallowing whatever it throws. */
    private void postCommand(Runnable command) {
        try {
            this.commands.execute(() -> {
                try {
                    command.run();
                } catch (Throwable t) {
                    LOGGER.debug("A queued libVLC call failed", t);
                }
            });
        } catch (Throwable t) {
            LOGGER.debug("Could not queue a libVLC call", t);
        }
    }

    /** Re-applies the configured volume on the command thread. */
    private void applyPendingVolume() {
        EmbeddedMediaPlayer current = this.player;
        if (current == null) {
            return;
        }
        current.audio().setVolume(this.pendingVolume);
        if (this.pendingVolume > 0) {
            current.audio().setMute(false);
        }
    }

    /**
     * Keeps a playing preload silent until it is resumed.
     *
     * <p>Switching the audio track off <em>before</em> playback is what makes a preload silent, but libVLC
     * creates its audio output asynchronously - so depending on the timing the track switch could land too
     * early and the first moments were audible. Re-asserting silence on the command thread (the same thread
     * every other control call uses) until the preload is resumed closes that race; the resume clears
     * {@link #preloaded} before it queues anything, so this can never mute the real audio.</p>
     */
    private void keepPreloadSilent() {
        postCommand(() -> {
            EmbeddedMediaPlayer current = this.player;
            if (!this.preloaded || current == null) {
                return;
            }
            setAudioEnabled(current, false);
            current.audio().setVolume(0);
            current.audio().setMute(true);
            if (!this.preloaded || this.player == null) {
                return;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            keepPreloadSilent();
        });
    }

    /** libVLC command line for the media players. */
    private String[] playerArguments(boolean startPaused) {
        List<String> arguments = new ArrayList<>(List.of("--no-video-title-show"));
        if (startPaused) {
            // The only reliable way to keep a preload silent: volume and mute are both ignored until libVLC
            // has created its audio output, so a preload would otherwise play its first moments out loud
            // while the game window is still coming up.
            arguments.add("--start-paused");
        }
        if (!this.debug) {
            arguments.add("--quiet");
        }
        // With debug logging on, libVLC is left at its default verbosity: only its own errors and
        // warnings are printed (a "--verbose=2" there would bury the log in decoder/plugin messages,
        // and the mod already reports buffer sizes, audio state and frame rates itself).
        if (this.hardwareDecoding) {
            // Software decoding of a 4K source cannot keep up (VLC's default is "--avcodec-hw=none"),
            // which shows up as a video that stutters and then ends after a handful of frames.
            // libVLC falls back to the software decoder when the driver cannot handle the stream.
            arguments.add("--avcodec-hw=any");
        }
        return arguments.toArray(new String[0]);
    }

    /**
     * Resolves where libVLC lives and prepares it - exactly once per process.
     *
     * <p>Several players are created (loading background, intro, replay after a resource reload) and
     * vlcj's native discovery must not run more than once: a second concurrent or repeated discovery can
     * fail and then silently disable a video. The result of the first attempt is cached here.</p>
     *
     * <p>The configured folder wins, then a mod-local drop-in folder, then the locations that Flatpak
     * sandboxes use to expose the host's libraries. The first directory that actually contains libvlc is
     * registered via {@code jna.library.path} (which vlcj's {@code JnaLibraryPathDirectoryProvider}
     * honours) and, when found, its plugins via {@code VLC_PLUGIN_PATH}.</p>
     *
     * @return {@code true} when libVLC is available
     */
    /**
     * Loads libVLC's native libraries as early as possible.
     *
     * <p>Called from the pre-launch entrypoint, before the game window exists: native loading takes about a
     * second, and the loading screen background video cannot show its first frame before that. Everything
     * in here is cached, so the regular preparation during a preload is a no-op afterwards.</p>
     */
    public static void warmUpNativeLibraries(String configuredPath) {
        prepareNativeOnce(configuredPath);
    }

    private static boolean prepareNativeOnce(String configuredPath) {
        synchronized (NATIVE_LOCK) {
            if (nativeChecked) {
                return nativeAvailable;
            }
            nativeChecked = true;
            for (Path candidate : libVlcCandidates(configuredPath)) {
                if (!Files.isDirectory(candidate) || !containsLibVlc(candidate)) {
                    continue;
                }
                applyDirectory(candidate);
                LOGGER.info("Using libvlc from {}", candidate);
                nativeAvailable = true;
                return true;
            }
            if (new NativeDiscovery().discover()) {
                nativeAvailable = true;
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
    private static String sandboxHint() {
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

    @Override
    public boolean isFinished() {
        return this.finished;
    }

    @Override
    public long cachedTimeMs() {
        return this.cachedTimeMs;
    }

    @Override
    public long timeMs() {
        EmbeddedMediaPlayer current = this.player;
        return current == null ? 0L : current.status().time();
    }

    @Override
    public long lengthMs() {
        // Only the value reported by libVLC's lengthChanged event is returned: the client thread asks
        // for this while drawing, and querying libVLC there can block when the player is in a bad state
        // (the same class of native call that froze the render thread once already).
        return Math.max(0L, this.lengthMs);
    }

    @Override
    public int sourceWidth() {
        // Cached only - see cacheSourceSize. The render thread asks for this every frame and must never
        // make a native call.
        return this.sourceWidth;
    }

    @Override
    public int sourceHeight() {
        return this.sourceHeight;
    }

    /**
     * Caches the true (unpadded) track dimensions from libVLC's media info.
     *
     * <p>Only ever called from libVLC's own event thread: asking libVLC for media info from the client
     * thread is a native call that can block and freeze the whole game. Until it is known, callers fall
     * back to the visible buffer size, which has the same aspect ratio.</p>
     */
    private void cacheSourceSize(MediaPlayer mediaPlayer) {
        if (this.sourceWidth > 0 && this.sourceHeight > 0) {
            return;
        }
        try {
            java.util.List<VideoTrackInfo> tracks = mediaPlayer.media().info().videoTracks();
            if (!tracks.isEmpty()) {
                VideoTrackInfo track = tracks.get(0);
                if (track.width() > 0 && track.height() > 0) {
                    this.sourceWidth = track.width();
                    this.sourceHeight = track.height();
                }
            }
        } catch (Throwable ignored) {
            // Media info is not ready yet - the next event tries again.
        }
    }

    @Override
    public void seekMs(long positionMs) {
        this.finished = false;
        postCommand(() -> {
            EmbeddedMediaPlayer current = this.player;
            if (current != null) {
                current.controls().setTime(Math.max(0L, positionMs));
            }
        });
    }

    @Override
    public void setVolume(int volumePercent) {
        this.pendingVolume = volumePercent;
        postCommand(this::applyPendingVolume);
    }

    @Override
    public void setPaused(boolean paused) {
        postCommand(() -> {
            EmbeddedMediaPlayer current = this.player;
            if (current != null) {
                current.controls().setPause(paused);
            }
        });
    }

    @Override
    public void setDebugLogging(boolean debug) {
        this.debug = debug;
    }

    @Override
    public void setHardwareDecoding(boolean hardwareDecoding) {
        this.hardwareDecoding = hardwareDecoding;
    }


    @Override
    public VideoFrameSink sink() {
        return this.sink;
    }

    @Override
    public void close() {
        EmbeddedMediaPlayer current = this.player;
        this.player = null;
        this.finished = false;
        MediaPlayerFactory currentFactory = this.factory;
        this.factory = null;
        if (current == null && currentFactory == null) {
            return;
        }
        if (!this.closing.compareAndSet(false, true)) {
            return;
        }
        // libvlc_media_player_stop() waits for libVLC's own threads and can block indefinitely when the
        // player is already in a bad state (a stalled video output, for example) - and calling it from
        // the client thread freezes the entire game (documented by a thread dump of a real freeze).
        // The teardown therefore happens on a daemon thread: the game simply carries on without this
        // player, and the sink is only released once libVLC is really done with it.
        Thread thread = new Thread(() -> {
            try {
                PLAYER_LOCK.lock();
                try {
                    if (current != null) {
                        current.controls().stop();
                        current.release();
                    }
                    if (currentFactory != null) {
                        currentFactory.release();
                    }
                } finally {
                    PLAYER_LOCK.unlock();
                }
                this.sink.close();
            } catch (Throwable t) {
                LOGGER.debug("Error while tearing down the libvlc player", t);
            } finally {
                this.closing.set(false);
            }
        }, "Titlescreen-Video-Teardown");
        thread.setDaemon(true);
        thread.start();
    }

    /** Forwards libVLC player events into our simple state flags. */
    private final class PlayerEvents extends MediaPlayerEventAdapter {
        private final Path file;
        private boolean audioReported;
        private boolean audioVolumeLogged;

        private PlayerEvents(Path file) {
            this.file = file;
        }

        @Override
        public void mediaPlayerReady(MediaPlayer mediaPlayer) {
            postCommand(VlcVideoPlayer.this::applyPendingVolume);
        }




        @Override
        public void playing(MediaPlayer mediaPlayer) {
            // The audio output only exists once playback starts, so (re)apply the volume - but from the
            // command thread: calling it from inside libVLC's event dispatch deadlocked the client.
            postCommand(VlcVideoPlayer.this::applyPendingVolume);
            postCommand(() -> logAudioStateOnce(VlcVideoPlayer.this.player));
            cacheSourceSize(mediaPlayer);
        }

        @Override
        public void timeChanged(MediaPlayer mediaPlayer, long newTime) {
            cachedTimeMs = newTime;
            cacheSourceSize(mediaPlayer);
            // The audio output is created a moment after playback starts - report its state the first
            // time libVLC actually knows the volume (before that it reports -1/muted, which is not an
            // error and only confuses whoever reads the log).
            if (this.audioVolumeLogged) {
                return;
            }
            this.audioVolumeLogged = true;
            postCommand(() -> logAudioStateOnce(VlcVideoPlayer.this.player));
        }

        /**
         * One-shot report of the video's audio tracks. A video without an audio track is by far the
         * most common reason for "the video has no sound", so say it out loud instead of leaving the
         * user guessing.
         */
        private void logAudioStateOnce(MediaPlayer mediaPlayer) {
            if (this.audioReported) {
                return;
            }
            this.audioReported = true;
            int tracks;
            try {
                tracks = mediaPlayer.media().info().audioTracks().size();
            } catch (Throwable t) {
                return;
            }
            if (tracks == 0) {
                LOGGER.info("{} has no audio track - nothing will be heard for this video",
                        this.file.getFileName());
                return;
            }
            LOGGER.info("{} has {} audio track(s), requested volume {}%",
                    this.file.getFileName(), tracks, pendingVolume);
        }

        @Override
        public void lengthChanged(MediaPlayer mediaPlayer, long newLength) {
            lengthMs = newLength;
        }

        @Override
        public void finished(MediaPlayer mediaPlayer) {
            finished = true;
        }

        @Override
        public void error(MediaPlayer mediaPlayer) {
            finished = true;
            LOGGER.warn("libvlc reported a playback error for {}", this.file);
        }
    }

    /** Tells libVLC which chroma/stride we want - RGBA keeps the alpha channel intact. */
    private final class FormatCallback implements BufferFormatCallback {

        private volatile int visibleWidth = -1;
        private volatile int visibleHeight = -1;

        @Override
        public BufferFormat getBufferFormat(int sourceWidth, int sourceHeight) {
            // Asking libVLC for an already scaled buffer is what keeps large sources (4K and up)
            // cheap: VLC scales during its own conversion, so we neither copy a 33 MB frame nor run
            // a per-pixel loop in Java. libVLC may still pad the buffer, and it reports the visible
            // picture size through newFormatSize().
            // Always the native size: no scaling inside libVLC, so a 4K video stays 4K on a 4K display
            // (and libVLC's scaler, which crashed natively more than once, is never involved).
            return new BufferFormat(CHROMA, sourceWidth, sourceHeight,
                    new int[]{sourceWidth * 4}, new int[]{sourceHeight});
        }

        @Override
        public void newFormatSize(int width, int height, int visibleWidth, int visibleHeight) {
            this.visibleWidth = visibleWidth;
            this.visibleHeight = visibleHeight;
        }

        @Override
        public void allocatedBuffers(ByteBuffer[] buffers) {
            // Buffers are owned by libVLC; we copy out of them.
        }
    }

    /** Receives decoded frames on a libVLC thread and stages them for the render thread. */
    private final class FrameCallback implements RenderCallback {

        private final FormatCallback format;

        private FrameCallback(FormatCallback format) {
            this.format = format;
        }

        @Override
        public void lock(MediaPlayer mediaPlayer) {
            // No locking needed - we copy the frame out immediately.
        }

        @Override
        public void unlock(MediaPlayer mediaPlayer) {
            // See lock().
        }

        @Override
        public void display(MediaPlayer mediaPlayer, ByteBuffer[] buffers, BufferFormat bufferFormat,
                            int displayWidth, int displayHeight) {
            if (buffers.length == 0 || buffers[0] == null) {
                return;
            }
            logFormatOnce(bufferFormat, displayWidth, displayHeight);
            int[] pitches = bufferFormat.getPitches();
            int pitch = pitches.length > 0 ? pitches[0] : displayWidth * 4;
            boolean bgrFallback = bufferFormat.getChroma() != null
                    && bufferFormat.getChroma().startsWith(RV32_PREFIX);
            VlcVideoPlayer.this.firstFrameSeen = true;
            int visibleWidth = this.format.visibleWidth;
            int visibleHeight = this.format.visibleHeight;
            sink.offerFrame(buffers[0], pitch, displayWidth, displayHeight,
                    visibleWidth, visibleHeight, bgrFallback, 0);
        }
    }
}
