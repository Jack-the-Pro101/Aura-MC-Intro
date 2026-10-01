package net.bluegaria.auraintro.client.video;

import com.mojang.blaze3d.platform.Window;
import net.bluegaria.auraintro.client.compat.McCompat;
import net.bluegaria.auraintro.client.config.AuraIntroConfig;
import net.bluegaria.auraintro.client.config.AuraIntroConfigHolder;
import net.bluegaria.auraintro.mixin.client.LoadingOverlayAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import org.joml.Matrix3x2fStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Drives the whole video intro: the loading scene's background, the hand-over to the title screen, the
 * button and progress-bar timing and what happens when the video ends.
 *
 * <p>There are two scenes and one video that runs through both. The loading scene draws the video
 * behind the loading bar and freezes on a frame until the game has finished loading; the title
 * screen then continues from exactly that frame. One player and one texture serve both scenes, so
 * the hand-over costs nothing: no second player, no second decoder, no seek, no stutter.</p>
 *
 * <p>Everything here runs on the client/render thread (client tick events, mixin callbacks during
 * rendering) and never calls into the decoder: decoding happens on the backend's own threads, and
 * control values are cheap volatile reads/writes. Per frame this class copies one decoded frame into a texture
 * and draws it once per scene, and all of its diagnostic reporting sits behind the {@code debugLogging}
 * config option.</p>
 */
public final class AuraIntroVideoManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("Aura-Intro");
    private static final Logger VIDEO_LOGGER = LoggerFactory.getLogger("Aura-Intro/Video");

    /** How many times starting the intro video is retried after a failed attempt. */
    private static final int MAX_SESSION_START_ATTEMPTS = 3;

    /**
     * How far the separate sound player may trail the picture before it is seeked back on: beyond
     * lip-sync noise, but far below the seconds-long gaps a stalled first launch used to leave.
     */
    private static final long AUDIO_RESYNC_LAG_MS = 250L;
    /** How far the sound may run ahead of the picture before it is pulled back (much rarer). */
    private static final long AUDIO_RESYNC_LEAD_MS = 750L;
    /** The gap must persist this long first - a transient hitch heals on its own. */
    private static final long AUDIO_RESYNC_PERSIST_MS = 400L;
    /** Minimum time between resync seeks, so a stuttering machine cannot machine-gun the decoder. */
    private static final long AUDIO_RESYNC_COOLDOWN_MS = 1500L;

    /** How long the hand-over may wait for the intro's first frames before the overlay is dropped anyway. */
    private static final long HANDOVER_TIMEOUT_MS = 4000L;
    /** How long a playing video may deliver no new frame before it counts as stalled. */
    private static final long PLAYBACK_STALL_MS = 1500L;

    private static final AuraIntroVideoManager INSTANCE = new AuraIntroVideoManager();

    private VideoPlayer player = new FfmpegVideoPlayer();
    private VideoPlayer backgroundPlayer = new FfmpegVideoPlayer();

    /**
     * The sound of a baked video, played by a player of its own.
     *
     * <p>A combined player shares one demux and decoder path between both streams, and while the game loads
     * a resource pack the 4K video cannot keep up - which delayed the audio packets with it and was heard as
     * the sound cutting in and out. Separate players have separate threads: the picture may drop frames, the
     * sound will not.</p>
     */
    private VideoPlayer audioPlayer = FfmpegVideoPlayer.audioOnly();

    // Closed-loop audio resync state (client tick): the gap between the picture and the sound of a
    // baked video played by two separate players. See resyncAudioToPicture.
    private long audioResyncSinceMs = -1L;
    private long lastAudioResyncMs = -1L;
    private long lastSoundProbeMs = Long.MIN_VALUE;
    private long lastSoundAdvanceMs;
    private long lastPictureProbeMs = Long.MIN_VALUE;
    private long lastPictureAdvanceMs;

    private final VideoTextureLayer introTexture =
            new VideoTextureLayer("aura-intro", "video_frame", "Aura-Intro intro video");
    private final VideoTextureLayer backgroundTexture =
            new VideoTextureLayer("aura-intro", "loading_background_frame", "Aura-Intro loading background");

    private boolean anchorSet;
    private LoadingOverlay anchoredOverlay;
    private long t0Ms;
    private boolean startRequested;
    private boolean sessionActive;
    private long introFramesUploaded;
    private long introStartMs;
    private boolean backgroundPreloadTriggered;
    /** Set once when the loading background turned out to produce no frames at all. */
    private boolean backgroundStallReported;
    /** Set once the intro timeline has a fixed start (the video's first displayed frame). */
    private boolean timelineLocked;
    /** False while a single baked video still shows its loading part - the intro is prepared but hidden. */
    private boolean introVisible;
    /**
     * One-way latch: once the video has been handed over to the title screen, the loading scene must never
     * draw again. Its sink keeps reporting a frame size for a moment after teardown, and its texture still
     * holds the frame the video was held on, so drawing it once more flashed that old hold frame over the
     * title screen right as the intro faded out to the panorama.
     */
    private boolean loadingLayerRetired;
    private long anchorSetMs;
    /** Updated by every client tick; the watchdog uses it to notice a frozen client. */
    private volatile long lastTickMs;

    private boolean watchdogStarted;
    private boolean stallDumpReported;
    /** Playback progress tracking: a video that stops delivering frames must not hold a scene. */
    private long lastBackgroundFrameCount = -1L;
    private long lastBackgroundProgressMs;
    private boolean backgroundAbandoned;
    /** One-shot debug report of how many frames the loading scene's video really produces. */
    private boolean loadingFrameRateReported;
    /** One-shot report of the A/V offset while the loading scene plays. */
    private boolean loadingSyncReported;
    /** Timestamp of the last heartbeat line (debug logging only). */
    private long lastHeartbeatMs;
    /** Set when something failed: the mod then leaves the vanilla screens alone for the rest of the session. */
    private volatile boolean broken;
    /** When a badge widget first appeared, so it can fade in from that moment (see titleBadgeAlpha). */
    private long badgeFirstSeenMs;
    /** When the last video session ended, which is when the wordmark starts fading in. */
    private long logoFadeStartMs;
    private long loadingFpsBaseline = -1L;
    private long loadingFpsBaselineMs;
    private long lastIntroFrameCount = -1L;
    private long lastIntroUploadCount = -1L;
    private long lastIntroUploadMs;
    /** One-shot intro playout rate check - see {@code updatePlaybackWatchdogs}. */
    private boolean introFrameRateReported;
    private long introFpsBaseline = -1L;
    private long introFpsBaselineMs;
    private long lastIntroProgressMs;
    private boolean introEndedEarly;
    /** How often starting the intro video failed - see {@code onClientTick}. */
    private int sessionStartFailures;
    private long lastSessionFailureMs;
    private long backgroundFramesUploaded;
    /** One-shot: when the first video pixels reached the screen, measured from the loading screen. */
    private boolean firstFrameOnScreenLogged;
    private long loadingOverlayFirstSeenMs;
    private boolean failed;
    private boolean ended;
    private boolean frozen;
    private long endFadeStartMs = -1L;
    private boolean introPlayedOnce;
    private volatile long sessionGeneration;
    private boolean buttonAlphaRestorePending;
    private boolean textureFormatLogged;
    /** Debug logging: one line per hand-over step, so "what is on screen at the hand-over" is answerable. */
    private final Set<String> tracedHandOverEvents = new HashSet<>();
    /** One-shot diagnostic for "the title screen shows through the video" - see the guard below. */
    private boolean layerSkipReported;

    // Loading screen background video (drawn behind the loading bar, frozen on its last frame).
    private boolean backgroundPreloadRequested;
    private boolean backgroundStartRequested;
    private boolean backgroundActive;
    private boolean backgroundFailed;
    private boolean backgroundFrozen;
    private boolean backgroundPlayedOnce;
    private boolean waitingForBackground;
    private long waitingSinceMs;
    private long backgroundStartMs;
    private volatile long backgroundGeneration;

    private AuraIntroVideoManager() {
    }

    public static AuraIntroVideoManager get() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------
    // Client tick state machine
    // ------------------------------------------------------------------

    public void onClientTick(Minecraft minecraft) {
        guard("the client tick", () -> tick(minecraft));
    }

    /** One-shot debug line when the menu music is first suppressed (see MusicManagerMixin). */
    public static void logMenuMusicSuppressed() {
        if (AuraIntroConfigHolder.get().general.debugLogging) {
            VIDEO_LOGGER.info("Menu music suppressed while the video plays");
        }
    }

    /**
     * Whether vanilla's menu music should stay quiet because the video is supplying the title
     * screen's sound (the {@code suppressMenuMusic} option). True from the moment a session starts
     * until the intro's sound is over - the fade-out, a stop, or a freeze after the end - so the
     * game's own soundtrack comes back exactly when the clip is done talking.
     */
    public boolean suppressMenuMusic() {
        AuraIntroConfig cfg = AuraIntroConfigHolder.get();
        if (!cfg.general.enabled || !cfg.general.suppressMenuMusic) {
            return false;
        }
        boolean introPlaying = (this.sessionActive || this.startRequested)
                && !this.ended && !this.player.isFinished();
        boolean backgroundPlaying = (this.backgroundActive || this.backgroundStartRequested)
                && !this.backgroundPlayer.isFinished();
        return introPlaying || backgroundPlaying;
    }

    private void tick(Minecraft minecraft) {
        this.lastTickMs = Util.getMillis();
        AuraIntroConfig cfg = AuraIntroConfigHolder.get();
        updatePlaybackWatchdogs(cfg);
        logHeartbeat(cfg);
        if (!cfg.general.enabled) {
            if (this.anchorSet || this.sessionActive || this.startRequested
                    || this.backgroundActive || this.backgroundStartRequested
                    || this.backgroundPreloadRequested) {
                stopSession(minecraft);
            }
            return;
        }

        // Preload the loading background video as soon as the client starts so its first frame is ready
        // before the loading screen appears (loading the decoder would otherwise show the vanilla loading
        // screen first). The preload is silent - see preloadLoadingBackground.
        preloadLoadingBackgroundOnce();

        // One-shot warning if the loading background is playing but never delivers a frame - that looks
        // like a loading screen that hangs with a black video, and is impossible to tell apart from a
        // slow game otherwise.
        if (cfg.general.debugLogging && this.backgroundActive && !this.backgroundStallReported
                && Util.getMillis() - this.backgroundStartMs > 3000L && this.backgroundFramesUploaded == 0
                && !this.backgroundPlayer.isFinished()) {
            this.backgroundStallReported = true;
            LOGGER.warn("Loading background video produced no frames {} ms after starting - "
                            + "decoding or scaling is stuck", Util.getMillis() - this.backgroundStartMs);
        }

        // Pause the preloaded background video on its first frame. The player normally pauses
        // itself the moment that frame exists (see FfmpegVideoPlayer's decode loop) - this stays
        // as the fallback for a player that did not, and for the debug log line.
        if (this.backgroundPlayer.consumePauseAfterFirstFrame()) {
            this.backgroundPlayer.setPaused(true);
            if (cfg.general.debugLogging) {
                LOGGER.info("Loading background video preloaded (first frame ready, paused)");
            }
        }
        if (this.backgroundActive && !this.loadingSyncReported
                && Util.getMillis() - this.backgroundStartMs >= 1200L) {
            this.loadingSyncReported = true;
            reportSync("loading scene");
        }

        if (this.audioPlayer.consumePauseAfterFirstFrame()) {
            // Fallback like the one above: the audio-only preload parks itself on its first chunk,
            // and holds its sound until it is resumed.
            this.audioPlayer.setPaused(true);
        }

        resyncAudioToPicture(cfg);

        // A failed start is usually a moment of native start-up contention; giving up for the whole
        // session would mean no intro video at all, so retry a couple of times.
        if (this.failed && this.sessionStartFailures < MAX_SESSION_START_ATTEMPTS
                && Util.getMillis() - this.lastSessionFailureMs > 3000L) {
            this.failed = false;
            this.sessionStartFailures++;
            this.lastSessionFailureMs = Util.getMillis();
            this.t0Ms = Util.getMillis() + cfg.timing.videoStartDelayMs;
            if (cfg.general.debugLogging) {
                LOGGER.info("Retrying the video intro start (attempt {})", this.sessionStartFailures + 1);
            }
            if (!beginSession(cfg)) {
                this.lastSessionFailureMs = Util.getMillis();
                this.failed = true;
            }
        }

        // If the intro player started but never delivers a frame, restart it: a title screen with the
        // video missing is the usual symptom of a failed decoder start, and it used to stay that way for
        // the whole session.
        if (this.sessionActive && this.introFramesUploaded == 0L && !this.player.isFinished()
                && !this.timelineLocked && Util.getMillis() - this.introStartMs > 2500L
                && this.sessionStartFailures < MAX_SESSION_START_ATTEMPTS) {
            LOGGER.warn("The video intro produced no frames {} ms after starting - restarting it (attempt {})",
                    Util.getMillis() - this.introStartMs, this.sessionStartFailures + 2);
            restartIntroSession(minecraft, cfg);
        }

        // The timeline normally starts with the video's first frame; if that never happens (broken
        // video, no decoder), fall back to the provisional timeline so the loading screen still clears.
        if (!this.timelineLocked && this.anchorSet && Util.getMillis() - this.anchorSetMs > 3000L) {
            this.timelineLocked = true;
            if (cfg.general.debugLogging) {
                LOGGER.info("Video intro produced no frame within {} ms - keeping the provisional timeline",
                        Util.getMillis() - this.anchorSetMs);
            }
        }

        Overlay overlay = McCompat.currentOverlay(minecraft);
        LoadingOverlay loadingOverlay = overlay instanceof LoadingOverlay candidate ? candidate : null;
        boolean replayAllowed = !this.introPlayedOnce || cfg.general.replayOnResourceReload;
        // Loading is finished once the overlay is ready to fade - or once vanilla has already dropped it,
        // which happens while this mod is waiting for the loading background video. Anchoring only inside
        // the overlay branch used to make that wait a dead end: the overlay vanished, the intro was never
        // anchored and no video appeared at all.
        boolean loadReady = loadingOverlay != null ? isLoadReady(loadingOverlay) : this.waitingForBackground;
        if (!this.anchorSet && !this.failed && replayAllowed
                && (this.waitingForBackground || loadReady)) {
            if (loadingOverlay != null && shouldWaitForBackgroundVideo(cfg)) {
                if (!this.waitingForBackground) {
                    this.waitingForBackground = true;
                    this.waitingSinceMs = Util.getMillis();
                    if (cfg.general.debugLogging) {
                        LOGGER.info("Loading finished - waiting for the loading background video to finish first");
                    }
                }
            } else {
                if (this.waitingForBackground && cfg.general.debugLogging) {
                    LOGGER.info("Continuing without waiting for the loading background video");
                }
                this.waitingForBackground = false;
                anchor(cfg, loadingOverlay);
            }
        } else if (loadingOverlay != null && loadingOverlay == this.anchoredOverlay && !this.failed
                && cfg.timing.overlayUnloadStyle == AuraIntroConfig.OverlayUnloadStyle.INSTANT
                && overlayCanBeUnloaded(cfg)) {
            // INSTANT unload: drop the loading scene (MOJANG logo) right now instead of letting
            // vanilla fade it out over another two seconds underneath the video.
            unloadOverlayNow(minecraft, loadingOverlay, cfg);
        }
        if (loadingOverlay != null) {
            if (this.loadingOverlayFirstSeenMs == 0L) {
                this.loadingOverlayFirstSeenMs = Util.getMillis();
            }
            maybeStartLoadingBackground(cfg);
        } else if (this.backgroundActive || this.backgroundStartRequested) {
            stopLoadingBackground(minecraft);
        }

        if (this.backgroundActive && this.backgroundPlayer != this.player) {
            handleBackgroundEndBehaviour(cfg);
        }

        if (!this.sessionActive) {
            return;
        }

        if (videoTimeMs() >= 0L) {
            handleEndBehaviour(cfg);
        }

        // Leaving the title screen (options, world select, ...) ends the intro for good.
        Screen currentScreen = McCompat.currentScreen(minecraft);
        if (currentScreen != null && !(currentScreen instanceof TitleScreen)) {
            stopSession(minecraft);
        }
    }

    /**
     * Keeps a baked video's separate sound player on the picture's timeline.
     *
     * <p>The picture player is scheduled against the wall clock and recovers from a stall by
     * catching up, while the sound plays on the audio device's own clock and simply continues from
     * wherever it was: after any long stall of the audio side (the first launches of a heavy
     * modpack - JIT, cold caches, native library extraction stall every thread for seconds at a
     * time) the sound stays behind the picture for the rest of the playback, because nothing
     * pulled the two open-loop clocks together. When the gap holds beyond lip-sync noise for a
     * moment, the sound is seeked to where the picture is; the seek flushes and re-primes it at
     * the right spot. Gated on both sides actually moving, so holds, pauses and ends never
     * trigger it.</p>
     */
    private void resyncAudioToPicture(AuraIntroConfig cfg) {
        if (!usesSeparateAudioPlayer(cfg)
                || (!this.backgroundActive && !this.sessionActive)) {
            this.audioResyncSinceMs = -1L;
            return;
        }
        long now = Util.getMillis();
        long pictureMs = this.player.cachedTimeMs();
        long soundMs = this.audioPlayer.cachedTimeMs();
        if (pictureMs < 0L || soundMs < 0L || this.backgroundFrozen || this.ended) {
            this.audioResyncSinceMs = -1L;
            this.lastSoundProbeMs = Long.MIN_VALUE;
            this.lastPictureProbeMs = Long.MIN_VALUE;
            return;
        }
        // Only correct while both sides actually move: a paused, held or ended stream makes any
        // gap a fact of the scene rather than a drift to repair.
        if (soundMs != this.lastSoundProbeMs) {
            this.lastSoundProbeMs = soundMs;
            this.lastSoundAdvanceMs = now;
        } else if (now - this.lastSoundAdvanceMs > 2000L) {
            this.audioResyncSinceMs = -1L;
            return;
        }
        if (pictureMs != this.lastPictureProbeMs) {
            this.lastPictureProbeMs = pictureMs;
            this.lastPictureAdvanceMs = now;
        } else if (now - this.lastPictureAdvanceMs > 2000L) {
            this.audioResyncSinceMs = -1L;
            return;
        }
        long lag = pictureMs - soundMs;
        boolean beyond = lag > AUDIO_RESYNC_LAG_MS || -lag > AUDIO_RESYNC_LEAD_MS;
        if (!beyond) {
            this.audioResyncSinceMs = -1L;
            return;
        }
        if (this.audioResyncSinceMs < 0L) {
            this.audioResyncSinceMs = now;
            return;
        }
        if (now - this.audioResyncSinceMs < AUDIO_RESYNC_PERSIST_MS
                || (this.lastAudioResyncMs > 0L && now - this.lastAudioResyncMs < AUDIO_RESYNC_COOLDOWN_MS)) {
            return;
        }
        this.lastAudioResyncMs = now;
        this.audioResyncSinceMs = -1L;
        if (cfg.general.debugLogging) {
            LOGGER.info("The sound ran {} ms {} the picture - seeking it to {} ms to resync",
                    Math.abs(lag), lag > 0L ? "behind" : "ahead of", pictureMs);
        }
        this.audioPlayer.seekMs(Math.max(0L, pictureMs));
    }

    // ------------------------------------------------------------------
    // Diagnostics (debug logging only)
    // ------------------------------------------------------------------

    /**
     * Traces one line per hand-over step while the hand-over is in progress (debug logging only).
     *
     * <p>Anything visible instead of the video at the hand-over - the panorama, the buttons - can only come
     * from the video layer not covering the screen during those frames, so the order and the opacity of each
     * step is what has to be on record rather than guessed at.</p>
     */
    private void traceHandOver(String event, String detail) {
        if (!AuraIntroConfigHolder.get().general.debugLogging) {
            return;
        }
        if (this.anchorSet && Util.getMillis() - this.anchorSetMs > 2000L) {
            return;
        }
        if (!this.tracedHandOverEvents.add(event)) {
            return;
        }
        LOGGER.info("Hand-over [{}] {}", event, detail);
    }

    // ------------------------------------------------------------------
    // Loading screen background video
    // ------------------------------------------------------------------

    /**
     * Called once at client startup so the background video's first frame is decoded and waiting before
     * the loading screen appears - otherwise the vanilla loading screen flashes before the video shows
     * up. The preload plays silently (volume 0, see {@link #preloadLoadingBackground()}), so nothing is
     * heard while the game window is still being set up. Safe to call more than once.
     */
    public void preloadLoadingBackgroundOnce() {
        if (this.backgroundPreloadTriggered) {
            return;
        }
        this.backgroundPreloadTriggered = true;
        startStallWatchdog();
        AuraIntroConfig cfg = AuraIntroConfigHolder.get();
        if (isBakedVideo(cfg)) {
            // A single baked video is played by ONE *video* player for both scenes: the loading scene plays
            // it and holds the freeze frame, the intro simply continues. Two video players on the same 4K
            // file (each with its own decoder and video output) is what crashed natively.
            // Its sound comes either from that player itself (one clock, no drift, but the sound shares the
            // video's demux) or from the separate audio-only player - see the audioPlayer field.
            this.backgroundPlayer = cfg.video.audioInSamePlayer
                    ? new FfmpegVideoPlayer()
                    : FfmpegVideoPlayer.silentVideo();
            this.player = this.backgroundPlayer;
        }
        preloadLoadingBackground();
    }

    /**
     * Watches for a frozen client and dumps every thread stack once when it happens. A freeze here is
     * almost always a Java thread waiting on a native lock, and without the dump there is no way to tell
     * which side is stuck - the game has to be killed, so the log is all that is left.
     */
    private void startStallWatchdog() {
        if (this.watchdogStarted || !AuraIntroConfigHolder.get().general.debugLogging) {
            return;
        }
        this.watchdogStarted = true;
        Thread thread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(5000L);
                } catch (InterruptedException e) {
                    return;
                }
                long stalled = Util.getMillis() - this.lastTickMs;
                if (this.lastTickMs == 0L || stalled < 2500L || this.stallDumpReported) {
                    continue;
                }
                this.stallDumpReported = true;
                LOGGER.error("The client has not ticked for {} ms - writing a thread dump", stalled);
                for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
                    Thread other = entry.getKey();
                    if (other == Thread.currentThread()) {
                        continue;
                    }
                    LOGGER.error("Thread \"{}\" ({}{})", other.getName(), other.getState(),
                            other.isDaemon() ? ", daemon" : "");
                    for (StackTraceElement frame : entry.getValue()) {
                        LOGGER.error("    at {}", frame);
                    }
                }
            }
        }, "Aura-Intro-Watchdog");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Decodes the background video's first frame, then leaves the player paused on it so the loading
     * screen can show it the moment it appears. Playback stays silent until the video is on screen
     * (see {@code maybeStartLoadingBackground}).
     */
    public void preloadLoadingBackground() {
        AuraIntroConfig cfg = AuraIntroConfigHolder.get();
        if (!cfg.general.enabled || !cfg.loadingBackground.enabled || this.backgroundPlayedOnce) {
            return;
        }
        Path path = VideoAssets.resolveVideo(cfg);
        if (path == null) {
            return;
        }
        // The loading scene is a background: dropping its alpha happens on the decode thread, which
        // is far cheaper than masking every 4K frame on the render thread.
        this.backgroundPlayer.sink().setForceOpaque(true);
        this.backgroundPreloadRequested = true;
        Thread thread = new Thread(() -> {
            this.backgroundPlayer.setDebugLogging(cfg.general.debugLogging);
            this.backgroundPlayer.setHardwareDecoding(cfg.video.hardwareDecoding);
            this.backgroundPlayer.setAudioLatencyMs(cfg.video.audioLatencyMs);
            this.backgroundPlayer.setAudioDevice(cfg.video.videoAudioDevice);
            // Volume 0: the preload decodes the first frame while the game is still starting up, and
            // playing the audio there would be heard long before anything is on screen.
            boolean ok = this.backgroundPlayer.preload(path, 0);
            if (usesSeparateAudioPlayer(cfg)) {
                // Silent again (volume 0) and paused on its first samples, so it cannot run ahead of the
                // loading scene that will play it either.
                configureAudioPlayer(cfg);
                this.audioPlayer.preload(path, 0);
            }
            synchronized (this) {
                this.backgroundPreloadRequested = false;
                if (!ok) {
                    this.backgroundFailed = true;
                }
            }
        }, "Aura-Intro-Background-Preload");
        thread.setDaemon(true);
        thread.start();
    }

    /** Starts the background video the first time the loading screen shows up. */
    private void maybeStartLoadingBackground(AuraIntroConfig cfg) {
        if (!cfg.loadingBackground.enabled || this.backgroundStartRequested || this.backgroundActive
                || this.backgroundFailed) {
            return;
        }
        if (this.backgroundPreloadRequested) {
            // The preload is still starting up - wait for it instead of starting a second session on
            // the same player, which would race with the start-up still in progress. The next frame resumes
            // from the preloaded first frame (isPreloaded() below).
            return;
        }
        if (this.backgroundPlayer.isPreloaded()) {
            if (!this.backgroundPlayer.isPreloadParked()
                    && (this.loadingOverlayFirstSeenMs == 0L
                        || Util.getMillis() - this.loadingOverlayFirstSeenMs < 5000L)) {
                // The preload is still decoding its first frame. Resume it once it is actually
                // parked on that frame - that is the whole point of the preload - and never mind a
                // video that never produces one: after the timeout the resume goes ahead anyway
                // (safe; the player disarms its self-pause when resumed).
                return;
            }
            this.backgroundPlayedOnce = true;
            this.backgroundStartMs = Util.getMillis();
            this.backgroundFrozen = false;
            this.backgroundActive = true;
            this.loadingLayerRetired = false;
            this.backgroundAbandoned = false;
            this.lastBackgroundFrameCount = -1L;
            this.lastBackgroundProgressMs = this.backgroundStartMs;
            // The preload parks on its first frame by itself (the decode thread pauses as soon as
            // that frame exists), so it is normally still at the very start of the clip. Should it
            // ever have run noticeably ahead anyway, restart at the beginning for the visible
            // playback - seeking flushes the decoder and briefly freezes the picture, so it is the
            // safety net, not the normal path.
            long preloadPositionMs = this.backgroundPlayer.cachedTimeMs();
            boolean restartFromStart = preloadPositionMs > 300L;
            if (restartFromStart) {
                this.backgroundPlayer.seekMs(0L);
            }
            // The volume was forced to 0 for the silent preload - apply the configured one before
            // resuming, so the very first chunk the audio thread writes is already audible.
            this.backgroundPlayer.setVolume(cfg.video.videoVolume);
            this.backgroundPlayer.resumeFromPreload();
            if (usesSeparateAudioPlayer(cfg)) {
                this.audioPlayer.setVolume(cfg.video.videoVolume);
                this.audioPlayer.resumeFromPreload();
                this.audioPlayer.setAudioDelayMs(cfg.video.audioDelayMs);
                if (cfg.video.audioDelayMs != 0 && cfg.general.debugLogging) {
                    LOGGER.info("Shifting the sound by {} ms to line it up with the picture",
                            cfg.video.audioDelayMs);
                }
            }
            if (cfg.general.debugLogging) {
                LOGGER.info("Loading background video resumed from its preloaded first frame "
                                + "(video position {} ms)", preloadPositionMs);
            }
            this.loadingSyncReported = false;
            return;
        }
        if (this.backgroundPlayedOnce && !cfg.general.replayOnResourceReload) {
            return;
        }
        Path path = VideoAssets.resolveVideo(cfg);
        if (path == null) {
            LOGGER.warn("No loading background video available - keeping the vanilla loading screen");
            this.backgroundFailed = true;
            return;
        }

        this.backgroundStartRequested = true;
        this.backgroundPlayedOnce = true;
        this.loadingLayerRetired = false;
        this.backgroundStartMs = Util.getMillis();
        this.backgroundFrozen = false;
        this.backgroundFramesUploaded = 0L;
        long generation = ++this.backgroundGeneration;
        Thread thread = new Thread(() -> {
            this.backgroundPlayer.setDebugLogging(cfg.general.debugLogging);
            this.backgroundPlayer.setHardwareDecoding(cfg.video.hardwareDecoding);
            this.backgroundPlayer.setAudioLatencyMs(cfg.video.audioLatencyMs);
            this.backgroundPlayer.setAudioDevice(cfg.video.videoAudioDevice);
            boolean ok = this.backgroundPlayer.start(path, cfg.video.videoVolume);
            if (ok && usesSeparateAudioPlayer(cfg)) {
                configureAudioPlayer(cfg);
                this.audioPlayer.start(path, cfg.video.videoVolume);
            }
            if (this.backgroundGeneration != generation) {
                this.backgroundPlayer.close();
                return;
            }
            synchronized (this) {
                this.backgroundStartRequested = false;
                if (ok) {
                    this.backgroundActive = true;
                } else {
                    this.backgroundFailed = true;
                }
            }
            if (ok && cfg.general.debugLogging) {
                LOGGER.info("Loading background video started: {}", path);
            }
        }, "Aura-Intro-Background-Init");
        thread.setDaemon(true);
        thread.start();
    }

    /** Applies the config to the separate sound player before it opens its file. */
    private void configureAudioPlayer(AuraIntroConfig cfg) {
        this.audioPlayer.setDebugLogging(cfg.general.debugLogging);
        this.audioPlayer.setAudioDevice(cfg.video.videoAudioDevice);
    }

    /** Freezes on the last frame (default) or loops, depending on the config. */
    private void handleBackgroundEndBehaviour(AuraIntroConfig cfg) {
        boolean finished = this.backgroundPlayer.isFinished() || this.backgroundAbandoned;
        if (!finished) {
            return;
        }
        if (cfg.loadingBackground.loop && !this.backgroundAbandoned) {
            this.backgroundPlayer.seekMs(0L);
            return;
        }
        if (!this.backgroundFrozen) {
            this.backgroundFrozen = true;
            if (!this.backgroundAbandoned) {
                // Pausing a player that stopped advancing is pointless (and can block), so the frozen
                // flag is enough in that case.
                this.backgroundPlayer.setPaused(true);
            }
            if (cfg.general.debugLogging) {
                long playedMs = Math.max(1L, Util.getMillis() - this.backgroundStartMs);
                LOGGER.info("Loading background video ended - holding its last frame "
                                + "({} frames in {} ms, {} fps)",
                        this.backgroundFramesUploaded, playedMs,
                        this.backgroundFramesUploaded * 1000L / playedMs);
            }
        }
    }

    private void stopLoadingBackground(Minecraft minecraft) {
        this.waitingForBackground = false;
        if (this.backgroundPlayer == this.player) {
            // Baked video: the loading scene and the intro share one player, so the hand-over must not
            // stop it - only the loading phase's bookkeeping ends here.
            this.backgroundActive = false;
            this.backgroundStartRequested = false;
            return;
        }
        boolean wasActive = this.backgroundActive || this.backgroundStartRequested
                || this.backgroundPlayer.isPreloaded();
        this.backgroundGeneration++;
        this.backgroundStartRequested = false;
        this.backgroundActive = false;
        this.backgroundFailed = false;
        this.backgroundFrozen = false;
        if (minecraft != null) {
            this.backgroundTexture.release(minecraft.getTextureManager());
        }
        if (wasActive) {
            this.backgroundPlayer.close();
        }
    }

    /**
     * Whether dropping the loading scene right now would leave the title screen on screen with no video
     * on it.
     *
     * <p>With a baked video the media timestamp says nothing here: the shared player is already past the
     * hold point when the hand-over happens, so {@code videoTimeMs() >= overlayUnloadAtMs} was true on
     * the very first tick - the overlay used to vanish before the intro layer had drawn a single frame,
     * and the title screen's panorama stayed visible until its first frame finally arrived. Wait for the
     * intro instead. A timeout keeps a video that never shows up from trapping the player in loading.</p>
     */
    private boolean overlayCanBeUnloaded(AuraIntroConfig cfg) {
        if (cfg.timing.overlayUnloadAtMs <= 0) {
            // Left to vanilla's own fade-out.
            return false;
        }
        if (!isBakedVideo(cfg)) {
            return videoTimeMs() >= cfg.timing.overlayUnloadAtMs;
        }
        return this.introFramesUploaded >= 2L
                || Util.getMillis() - this.anchorSetMs > HANDOVER_TIMEOUT_MS;
    }

    /**
     * Loading overlay, before its logo is drawn: the video behind the loading bar.
     *
     * <p>The preload decodes the first frame and holds it paused before the loading screen appears, so that
     * frame can be drawn on the very first rendered frame. Waiting for the tick to activate the background
     * instead makes the vanilla loading background show for the first frame or two - and the first frame of
     * a resource reload is slow, so that gap is very visible.</p>
     */
    public void drawLoadingBackground(GuiGraphicsExtractor graphics) {
        guard("drawing the loading background", () -> drawLoadingBackgroundNow(graphics));
    }

    private void drawLoadingBackgroundNow(GuiGraphicsExtractor graphics) {
        AuraIntroConfig cfg = AuraIntroConfigHolder.get();
        VideoPlayer player = this.backgroundPlayer;
        if (!cfg.general.enabled || !cfg.loadingBackground.enabled || this.backgroundFailed
                || this.loadingLayerRetired || graphics == null || player == null) {
            return;
        }
        // The loading screen renders before the client's first tick (and its first tick can trail
        // the window by a second of start-up work), and the parked preload - sound included -
        // should resume the moment it can, not one tick later than that. The draw path runs on
        // the same thread as the tick, and starting is idempotent, so this only makes it earlier.
        maybeStartLoadingBackground(cfg);
        if (!this.backgroundActive && !hasFrame(player)) {
            return;
        }
        if (this.backgroundTexture.upload(player, cfg.video.videoMaxFps)) {
            this.backgroundFramesUploaded++;
        }

        long elapsed = Util.getMillis() - this.backgroundStartMs;
        float alpha = 1.0F;
        int fadeIn = cfg.loadingBackground.fadeInMs;
        if (fadeIn > 0 && elapsed < fadeIn) {
            alpha = Math.max(0.0F, elapsed / (float) fadeIn);
        }
        alpha *= cfg.video.videoOpacity / 100.0F;
        if (alpha <= 0.004F) {
            return;
        }
        logFirstFrameOnScreen();
        this.backgroundTexture.draw(graphics, player, cfg.video.videoFit, alpha);
    }

    private static boolean hasFrame(VideoPlayer player) {
        return player != null && player.sink().width() > 0 && player.sink().height() > 0;
    }

    /**
     * One-shot: reports how long the loading screen showed the vanilla background before the first video
     * pixels were on it. That gap is the vanilla loading screen being visible, so it is the measurement
     * for "the window should open onto the video's first frame".
     */
    private void logFirstFrameOnScreen() {
        if (this.firstFrameOnScreenLogged) {
            return;
        }
        this.firstFrameOnScreenLogged = true;
        if (!AuraIntroConfigHolder.get().general.debugLogging) {
            return;
        }
        long reference = this.loadingOverlayFirstSeenMs > 0L
                ? this.loadingOverlayFirstSeenMs
                : this.backgroundStartMs;
        LOGGER.info("Video on screen {} ms after the loading screen appeared "
                        + "(anything before that was the vanilla loading background)",
                Math.max(0L, Util.getMillis() - reference));
    }

    private void anchor(AuraIntroConfig cfg, LoadingOverlay overlay) {
        this.anchorSet = true;
        this.anchoredOverlay = overlay;
        this.anchorSetMs = Util.getMillis();
        this.timelineLocked = false;
        this.introEndedEarly = false;
        this.lastIntroFrameCount = -1L;
        this.lastIntroProgressMs = Util.getMillis();
        this.introPlayedOnce = true;
        // From here on the title screen owns the video: the loading scene's layer is never drawn again
        // (see loadingLayerRetired), so its frozen hold frame cannot reappear over the title screen.
        this.loadingLayerRetired = true;
        traceHandOver("loading layer retired", "the title screen owns the video from here");
        this.t0Ms = Util.getMillis() + cfg.timing.videoStartDelayMs;
        if (cfg.general.debugLogging) {
            LOGGER.info("Loading finished - video intro starts after a {} ms delay", cfg.timing.videoStartDelayMs);
        }
        if (isBakedVideo(cfg)) {
            // One player serves both scenes: it is already playing (held on the freeze frame), so the
            // intro just continues - no second player, no second decoder, no seek, no stutter.
            this.sessionActive = true;
            this.introVisible = true;
            this.introFramesUploaded = 0L;
            this.introStartMs = Util.getMillis();
            this.timelineLocked = false;
            this.introEndedEarly = false;
            this.lastIntroFrameCount = -1L;
            this.lastIntroProgressMs = Util.getMillis();
            this.lastIntroUploadCount = -1L;
            this.lastIntroUploadMs = Util.getMillis();
            this.player.setVolume(cfg.video.videoVolume);
            this.audioPlayer.setVolume(cfg.video.videoVolume);
            this.audioPlayer.setAudioDelayMs(cfg.video.audioDelayMs);
            this.audioPlayer.setPaused(false);
            // The intro continues from wherever the shared player is - including the frame the
            // loading scene froze on (holdAtMs): that freeze pauses the very player that now has
            // to keep playing, and this is the only place that releases it. A no-op whenever
            // nothing paused it.
            this.player.setPaused(false);
            reportSync("title screen");
            if (cfg.general.debugLogging) {
                LOGGER.info("Video intro continues from the held frame ({} ms)",
                        cfg.loadingBackground.holdAtMs);
            }
            // The layer that draws the video has to hold a frame before anything can expose the title
            // screen. Vanilla's own overlay fade-out can remove the loading overlay the moment the reload
            // finishes (before this mod's hold takes effect), and an empty layer means the title screen is
            // drawn with nothing over it - the panorama and buttons flash through until the next frame.
            this.introTexture.warmUp(this.player);
            traceHandOver("anchor", "sessionActive + introVisible, holdAtMs=" + cfg.loadingBackground.holdAtMs);
            return;
        }
        if (!beginSession(cfg)) {
            this.failed = true;
        }
    }

    /**
     * Replicates what vanilla does when its own fade-out finishes: finish the reload (reporting any
     * load failure) and drop the overlay immediately.
     */
    private void unloadOverlayNow(Minecraft minecraft, LoadingOverlay overlay, AuraIntroConfig cfg) {
        traceHandOver("overlay drop", "videoTimeMs=" + videoTimeMs()
                + " introFramesUploaded=" + this.introFramesUploaded);
        LoadingOverlayAccessor accessor = (LoadingOverlayAccessor) overlay;
        try {
            Optional<Throwable> failure = Optional.empty();
            ReloadInstance reload = accessor.getReload();
            if (reload != null) {
                try {
                    reload.checkExceptions();
                } catch (Throwable t) {
                    failure = Optional.of(t);
                }
            }
            accessor.getOnFinish().accept(failure);
        } catch (Throwable t) {
            LOGGER.warn("Failed to finish the loading overlay cleanly", t);
        }
        McCompat.clearOverlay(minecraft);

        // Vanilla re-initialises the screen right after onFinish, mirror that here.
        Screen screen = McCompat.currentScreen(minecraft);
        if (screen != null) {
            McCompat.reinitScreen(screen, minecraft,
                    minecraft.getWindow().getGuiScaledWidth(), minecraft.getWindow().getGuiScaledHeight());
        }
        if (cfg.general.debugLogging) {
            LOGGER.info("Loading overlay unloaded at video timestamp {} ms", videoTimeMs());
        }
    }

    /** Starts the decoder on a background thread so the first real frame never blocks loading. */
    private boolean beginSession(AuraIntroConfig cfg) {
        Path path = VideoAssets.resolveVideo(cfg);
        if (path == null) {
            LOGGER.warn("No video available - skipping the video intro (vanilla behaviour is kept)");
            return false;
        }
        this.startRequested = true;
        // One video for both scenes: when the loading screen held a freeze frame, the intro
        // continues from that exact frame; otherwise it plays from the start.
        long startAtMs = cfg.loadingBackground.holdAtMs > 0
                ? cfg.loadingBackground.holdAtMs : 0L;
        if (startAtMs > 0L && cfg.general.debugLogging) {
            LOGGER.info("Video intro continues from {} ms of the baked video", startAtMs);
        }
        long generation = ++this.sessionGeneration;
        Thread thread = new Thread(() -> {
            this.player.setDebugLogging(cfg.general.debugLogging);
            this.player.setHardwareDecoding(cfg.video.hardwareDecoding);
            this.player.setAudioLatencyMs(cfg.video.audioLatencyMs);
            this.player.setAudioDevice(cfg.video.videoAudioDevice);
            boolean ok = this.player.start(path, cfg.video.videoVolume);
            if (usesSeparateAudioPlayer(cfg)) {
                // Reached when a baked video is retried (normally its sound is already playing): restart it
                // with the video so both stay together.
                configureAudioPlayer(cfg);
                this.audioPlayer.start(path, cfg.video.videoVolume);
                this.audioPlayer.setAudioDelayMs(cfg.video.audioDelayMs);
            }
            if (ok && startAtMs > 0L) {
                // Seeking on the player's own thread, before the intro is shown, so its timeline (which
                // starts with the first displayed frame) is relative to the baked intro.
                this.player.seekMs(startAtMs);
                if (isBakedVideo(cfg)) {
                    this.audioPlayer.seekMs(startAtMs);
                }
            }
            if (this.sessionGeneration != generation) {
                // The session was torn down while the decoder was still starting up.
                this.player.close();
                return;
            }
            synchronized (this) {
                this.startRequested = false;
                if (ok) {
                    this.sessionActive = true;
                    // A prepared (baked) intro stays invisible until the loading scene hands over.
                    this.introVisible = !isBakedVideo(cfg);
                    this.introFramesUploaded = 0L;
                    this.introStartMs = Util.getMillis();
                } else {
                    this.failed = true;
                    this.sessionStartFailures++;
                    this.lastSessionFailureMs = Util.getMillis();
                }
            }
            if (ok && cfg.general.debugLogging) {
                LOGGER.info("Video intro started: {}", path);
            }
        }, "Aura-Intro-Video-Init");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    private void handleEndBehaviour(AuraIntroConfig cfg) {
        if (!this.ended) {
            switch (cfg.timing.endBehaviour) {
                case LOOP_REGION -> {
                    long loopEnd = cfg.timing.loopEndMs > 0 ? cfg.timing.loopEndMs : this.player.lengthMs();
                    if (this.introEnded() || (loopEnd > 0L && this.player.timeMs() >= loopEnd)) {
                        this.player.seekMs(cfg.timing.loopStartMs);
                    }
                }
                case FADE_OUT_TO_PANORAMA -> {
                    if (this.introEnded() && this.endFadeStartMs < 0L) {
                        this.endFadeStartMs = Util.getMillis();
                        // The wordmark fades in from here as well, so the video dissolving away and the
                        // wordmark appearing are one movement. Set the fade durations to match (videoFadeOutMs
                        // and textFadeInDurationMs) for a perfectly symmetric cross-fade.
                        this.logoFadeStartMs = this.endFadeStartMs;
                        if (cfg.general.debugLogging) {
                            long playedMs = Math.max(1L, Util.getMillis() - this.introStartMs);
                            LOGGER.info("Video ended after {} frames in {} ms ({} fps) - fading out over {} ms",
                                    this.introFramesUploaded, playedMs,
                                    this.introFramesUploaded * 1000L / playedMs, cfg.timing.videoFadeOutMs);
                        }
                    }
                }
                case FREEZE_LAST_FRAME -> {
                    if (this.introEnded() && !this.frozen) {
                        this.frozen = true;
                        // Nothing is fading out here, so this is simply when the wordmark starts appearing.
                        this.logoFadeStartMs = Util.getMillis();
                        this.player.setPaused(true);
                        if (cfg.general.debugLogging) {
                            LOGGER.info("Video ended - freezing on the last frame");
                        }
                    }
                }
            }
        }

        if (this.endFadeStartMs > 0L && videoAlpha(cfg) <= 0.0F) {
            this.ended = true;
            stopSession(Minecraft.getInstance());
        }
    }

    /** True while one video serves both the loading scene and the intro (the only architecture). */
    private static boolean isBakedVideo(AuraIntroConfig cfg) {
        return cfg.general.enabled && cfg.loadingBackground.enabled;
    }

    /**
     * Tears the intro session down and starts it again from the beginning. Used when the player started
     * without ever producing a frame.
     */
    private void restartIntroSession(Minecraft minecraft, AuraIntroConfig cfg) {
        LoadingOverlay anchored = this.anchoredOverlay;
        stopSession(minecraft);
        this.sessionStartFailures++;
        this.lastSessionFailureMs = Util.getMillis();
        // stopSession() clears the anchor; the video is retried on a fresh timeline, but the overlay
        // bookkeeping has to survive so the loading screen still clears at the configured timestamp.
        this.anchorSet = anchored != null;
        this.anchoredOverlay = anchored;
        this.anchorSetMs = Util.getMillis();
        this.timelineLocked = false;
        this.introEndedEarly = false;
        this.lastIntroFrameCount = -1L;
        this.lastIntroProgressMs = this.anchorSetMs;
        this.t0Ms = Util.getMillis() + cfg.timing.videoStartDelayMs;
        if (!beginSession(cfg)) {
            this.failed = true;
            this.lastSessionFailureMs = Util.getMillis();
        }
    }

    private void stopSession(Minecraft minecraft) {
        boolean wasActive = this.sessionActive || this.startRequested;
        if (this.ended) {
            // The video ended before the buttons finished fading in - make sure they end up
            // fully visible instead of being stuck at a partial alpha.
            this.buttonAlphaRestorePending = true;
        }
        this.sessionGeneration++;
        this.sessionActive = false;
        this.startRequested = false;
        this.anchorSet = false;
        this.anchoredOverlay = null;
        this.waitingForBackground = false;
        this.waitingSinceMs = 0L;
        this.ended = false;
        this.frozen = false;
        this.failed = false;
        this.endFadeStartMs = -1L;
        if (minecraft != null) {
            this.introTexture.release(minecraft.getTextureManager());
        }
        if (wasActive) {
            this.player.close();
        }
        this.audioPlayer.close();
        stopLoadingBackground(minecraft);
    }

    /**
     * One-shot flag telling the title screen mixin to force full button opacity for a frame.
     */
    public boolean consumeButtonAlphaRestore() {
        if (!this.buttonAlphaRestorePending) {
            return false;
        }
        this.buttonAlphaRestorePending = false;
        return true;
    }

    public void shutdown() {
        stopSession(Minecraft.getInstance());
    }

    // ------------------------------------------------------------------
    // Timing helpers - all relative to video timestamp 0
    // ------------------------------------------------------------------

    private long videoTimeMs() {
        return this.anchorSet ? Util.getMillis() - this.t0Ms : -1L;
    }

    /** {@code true} once loading finished and the intro is (about to be) playing. */
    private boolean holdingEligible() {
        return this.anchorSet && !this.failed;
    }

    /**
     * Consulted by {@link net.bluegaria.auraintro.mixin.client.LoadingOverlayMixin}:
     * while this returns {@code true} vanilla is not allowed to start fading the overlay out, so
     * the Mojang logo stays on screen until the configured video timestamp.
     */
    public boolean shouldHoldOverlay() {
        AuraIntroConfig cfg = AuraIntroConfigHolder.get();
        if (!cfg.general.enabled) {
            return false;
        }
        if (this.waitingForBackground) {
            // Keep the loading screen (and its background video) up until the clip is done.
            return true;
        }
        if (cfg.timing.overlayUnloadAtMs <= 0 || !holdingEligible()) {
            return false;
        }
        return videoTimeMs() < cfg.timing.overlayUnloadAtMs;
    }

    /**
     * {@code true} while the loading screen should stay up because the background video has not
     * reached its end yet (and the config asks for the next scene to wait for it).
     */
    private boolean shouldWaitForBackgroundVideo(AuraIntroConfig cfg) {
        if (!cfg.loadingBackground.enabled || !cfg.loadingBackground.waitForVideoToFinish
                || this.backgroundFailed) {
            return false;
        }
        if (!this.backgroundActive) {
            // Not playing (never resumed, already stopped or torn down): nothing to wait for. The
            // preload flag alone is not enough - it stays set when the video was resumed through the
            // non-preload path, which used to keep this wait alive forever.
            return false;
        }
        if (this.backgroundPlayer.isFinished() || this.backgroundAbandoned || this.backgroundFrozen) {
            return false;
        }
        int maxWait = cfg.loadingBackground.maxWaitForVideoMs;
        if (maxWait > 0 && this.waitingSinceMs != 0
                && Util.getMillis() - this.waitingSinceMs > maxWait) {
            if (cfg.general.debugLogging) {
                LOGGER.info("Waited {} ms for the loading background video - continuing anyway", maxWait);
            }
            return false;
        }
        return true;
    }

    /**
     * Runs one step of the mod, and on any failure disables it rather than letting the exception escape into
     * Minecraft's render loop or tick. A video intro is never worth a crash: everything here falls back to the
     * vanilla loading and title screens.
     */
    private void guard(String what, Runnable step) {
        if (this.broken) {
            return;
        }
        try {
            step.run();
        } catch (Throwable t) {
            this.broken = true;
            LOGGER.error("The video intro failed while {} - it is disabled for the rest of this session and "
                    + "the vanilla screens are used", what, t);
            try {
                stopSession(Minecraft.getInstance());
            } catch (Throwable ignored) {
                // Already gone far enough wrong; nothing else to do.
            }
        }
    }

    /** True when a baked video's sound is played by its own player rather than by the video player. */
    private static boolean usesSeparateAudioPlayer(AuraIntroConfig cfg) {
        return isBakedVideo(cfg) && !cfg.video.audioInSamePlayer;
    }

    /**
     * Periodic debug line while a video is on screen (every 15 s).
     *
     * <p>Written so that a stalled scene is visible in the log: the frame counters say whether frames are
     * still produced and drawn, the alpha says whether the scene is fading, and the positions say whether
     * the two players are drifting apart. A freeze that leaves no other trace at all is otherwise
     * impossible to tell apart from a log that simply ended.</p>
     */
    private void logHeartbeat(AuraIntroConfig cfg) {
        if (!cfg.general.debugLogging) {
            return;
        }
        long now = Util.getMillis();
        if (now - this.lastHeartbeatMs < 15000L) {
            return;
        }
        this.lastHeartbeatMs = now;
        if (!this.backgroundActive && !this.sessionActive) {
            return;
        }
        LOGGER.info("Heartbeat: loading {} produced / {} drawn, intro {} produced / {} drawn, alpha {}, "
                        + "picture {} ms, sound {} (backgroundActive={} sessionActive={} frozen={} ended={})",
                this.backgroundPlayer.sink().producedFrames(), this.backgroundFramesUploaded,
                this.player.sink().producedFrames(), this.introFramesUploaded, videoAlpha(cfg),
                this.player.cachedTimeMs(),
                this.audioPlayer.cachedTimeMs() < 0L ? "n/a" : Long.toString(this.audioPlayer.cachedTimeMs()),
                this.backgroundActive, this.sessionActive, this.backgroundFrozen, this.ended);
    }

    /**
     * Reports where the picture and a separate sound player are, as each reports itself (debug
     * logging only) - the honest way to see how far apart two players with two clocks are.
     */
    private void reportSync(String where) {
        if (!AuraIntroConfigHolder.get().general.debugLogging
                || (!this.sessionActive && !this.backgroundActive)) {
            return;
        }
        long pictureMs = this.player.cachedTimeMs();
        long soundMs = this.audioPlayer.cachedTimeMs();
        if (pictureMs < 0L || soundMs < 0L) {
            return;
        }
        long difference = soundMs - pictureMs;
        LOGGER.info("A/V at {}: picture {} ms, sound {} ms (sound {} ms {} the picture)",
                where, pictureMs, soundMs, Math.abs(difference),
                difference > 0L ? "ahead of" : "behind");
    }

    /**
     * Watches both players for a playback that stops advancing, and gives up on it.
     *
     * <p>A decoder occasionally stops reporting progress for a media it is playing (never firing its end
     * event) - the loading screen then waits for the background video forever and the intro never
     * starts, which looks exactly like "the second video did not play". A few hundred milliseconds of
     * progress tracking is enough to notice and move on.</p>
     */
    private void updatePlaybackWatchdogs(AuraIntroConfig cfg) {
        long now = Util.getMillis();

        // Freeze-frame hold for a single baked video: pause the loading scene at the configured frame so
        // the game can finish loading, then continue from that frame as the intro.
        if (this.backgroundActive && !this.backgroundFrozen && cfg.loadingBackground.holdAtMs > 0
                && this.backgroundPlayer.cachedTimeMs() >= cfg.loadingBackground.holdAtMs) {
            this.backgroundFrozen = true;
            this.backgroundPlayer.setPaused(true);
            if (usesSeparateAudioPlayer(cfg)) {
                // The sound stops with the picture while the game finishes loading.
                this.audioPlayer.setPaused(true);
                // Both players are paused now, so this cannot be heard - and seeking costs a re-prime that
                // is only paid when playback resumes. Parking both on the very same frame is what keeps
                // them together afterwards: the picture and the sound are separate players with their own
                // clocks, so their positions have to be equalised while there is a chance to do it.
                this.backgroundPlayer.seekMs(cfg.loadingBackground.holdAtMs);
                this.audioPlayer.seekMs(cfg.loadingBackground.holdAtMs);
                reportSync("freeze frame");
            }
            if (cfg.general.debugLogging) {
                LOGGER.info("Loading background video held at {} ms - waiting for the game to finish loading "
                                + "(sound at {} ms)",
                        cfg.loadingBackground.holdAtMs, this.audioPlayer.cachedTimeMs());
            }
        }

        // How fast the loading scene's video actually plays. A 4K source the machine cannot decode (a 10-bit
        // one in software, for example) shows up here first: frames trickle in, the picture stutters and the
        // audio needs the same CPU, so this is the number that explains both.
        if (this.backgroundActive && !this.backgroundFrozen && !this.loadingFrameRateReported) {
            long produced = this.backgroundPlayer.sink().producedFrames();
            if (this.loadingFpsBaseline < 0L) {
                this.loadingFpsBaseline = produced;
                this.loadingFpsBaselineMs = now;
            } else if (now - this.loadingFpsBaselineMs >= 1500L) {
                this.loadingFrameRateReported = true;
                long elapsedMs = Math.max(1L, now - this.loadingFpsBaselineMs);
                long frames = produced - this.loadingFpsBaseline;
                long fps = frames * 1000L / elapsedMs;
                if (fps < 15L) {
                    LOGGER.warn("The loading video only produced {} frames per second ({} frames in {} ms, {} "
                                    + "drawn) - this machine cannot decode it fast enough, and that is also "
                                    + "what starves its audio. Use a lighter source video (8-bit decodes far "
                                    + "faster than 10-bit 4:2:2) and use a smaller source video.",
                            fps, frames, elapsedMs, this.backgroundFramesUploaded);
                } else if (cfg.general.debugLogging) {
                    LOGGER.info("Loading video is playing at {} fps ({} frames produced, {} drawn in {} ms)",
                            fps, frames, this.backgroundFramesUploaded, elapsedMs);
                }
            }
        }

        if (this.backgroundPlayer != this.player && this.backgroundActive && !this.backgroundFrozen) {
            long produced = this.backgroundPlayer.sink().producedFrames();
            if (produced != this.lastBackgroundFrameCount) {
                this.lastBackgroundFrameCount = produced;
                this.lastBackgroundProgressMs = now;
            } else if (!this.backgroundAbandoned
                    && now - this.lastBackgroundProgressMs > PLAYBACK_STALL_MS) {
                this.backgroundAbandoned = true;
                LOGGER.warn("Loading background video stopped delivering frames after {} frames - "
                        + "continuing without it", this.backgroundFramesUploaded);
            }
        }

        if (this.sessionActive && this.introVisible && !this.ended) {
            long produced = this.player.sink().producedFrames();
            if (produced != this.lastIntroFrameCount) {
                this.lastIntroFrameCount = produced;
                this.lastIntroProgressMs = now;
            } else if (!this.introEndedEarly
                    && now - this.lastIntroProgressMs > PLAYBACK_STALL_MS * 2) {
                this.introEndedEarly = true;
                if (cfg.general.debugLogging) {
                    LOGGER.info("Video intro stopped delivering frames after {} frames - treating it as ended",
                            this.introFramesUploaded);
                }
            }

            // What the player actually sees is the uploaded texture, not the frames the decoder produces: if
            // the uploads stop while the vout keeps producing, the picture freezes silently and nothing
            // would ever end the intro. Watch the uploads too and end the scene on a stall there.
            if (this.introFramesUploaded != this.lastIntroUploadCount) {
                this.lastIntroUploadCount = this.introFramesUploaded;
                this.lastIntroUploadMs = now;
            } else if (!this.introEndedEarly
                    && now - this.lastIntroUploadMs > PLAYBACK_STALL_MS * 2) {
                this.introEndedEarly = true;
                LOGGER.warn("Video intro stopped updating the screen for {} ms (produced {}, drawn {}) - "
                                + "ending it so the title screen takes over",
                        now - this.lastIntroUploadMs, produced, this.introFramesUploaded);
            }

            // A low frame rate is the fingerprint of a video the CPU cannot decode fast enough (a 4K source
            // with software decoding). It does not trip the stall watchdogs - frames still trickle in - so
            // it used to show up only as "the video freezes and the intro never ends".
            if (!this.introFrameRateReported) {
                if (this.introFpsBaseline < 0L) {
                    this.introFpsBaseline = produced;
                    this.introFpsBaselineMs = now;
                } else if (now - this.introFpsBaselineMs >= 2000L) {
                    this.introFrameRateReported = true;
                    long elapsedMs = Math.max(1L, now - this.introFpsBaselineMs);
                    long frames = produced - this.introFpsBaseline;
                    long fps = frames * 1000L / elapsedMs;
                    if (fps < 15L) {
                        LOGGER.warn("The video intro only produced {} frames per second ({} frames in {} ms, "
                                        + "{} drawn) - the decoder cannot keep up, so it will look "
                                        + "frozen and never reach its end. Use a smaller source video.",
                                fps, frames, elapsedMs, this.introFramesUploaded);
                    } else if (cfg.general.debugLogging) {
                        LOGGER.info("Video intro is playing at {} fps ({} frames produced, {} drawn in {} ms, "
                                        + "current alpha {})",
                                fps, frames, this.introFramesUploaded, elapsedMs, videoAlpha(cfg));
                    }
                }
            }
        }
    }

    /** True once the intro video reached its end (by the backend's end flag or by the watchdog). */
    private boolean introEnded() {
        return this.player.isFinished() || this.introEndedEarly || mediaReachedEnd();
    }

    /**
     * Whether the media's playhead has reached the end of the file.
     *
     * <p>The backend's "finished" flag is not something to wait for forever: the stall watchdogs
     * stay quiet as long as frames still trickle in - so a video that plays to its end could sit there
     * forever with the title screen never taking over. The length and the playhead are cached values,
     * so they are safe to read here, and the last half second counts as the end.</p>
     */
    private boolean mediaReachedEnd() {
        long length = this.player.lengthMs();
        if (length <= 0L) {
            return false;
        }
        long time = this.player.cachedTimeMs();
        return time > 0L && time >= length - 500L;
    }

    /**
     * Progress bar alpha. {@code vanillaFade} is returned whenever the overlay is not under our
     * control, so vanilla behaviour is fully preserved if the mod is disabled or the video failed.
     */
    public float progressBarAlpha(float vanillaFade) {
        AuraIntroConfig cfg = AuraIntroConfigHolder.get();
        if (!cfg.general.enabled || !holdingEligible() || cfg.timing.overlayUnloadAtMs <= 0) {
            return vanillaFade;
        }
        long time = videoTimeMs();
        int start = cfg.timing.progressBarFadeStartMs;
        if (time < start) {
            return 1.0F;
        }
        int duration = cfg.timing.progressBarFadeDurationMs;
        if (duration <= 0) {
            return 0.0F;
        }
        return Mth.clamp(1.0F - (time - start) / (float) duration, 0.0F, 1.0F);
    }

    /**
     * Fade factor for the title screen's texts - the version/mod-count line and the splash - or {@code -1}
     * when they are left to vanilla.
     *
     * <p>{@link net.bluegaria.auraintro.mixin.client.TitleScreenMixin} scales their alpha with this, so
     * they start fully transparent and fade in on the configured timetable instead of with vanilla's own
     * screen fade, exactly like the buttons.</p>
     */
    public float titleTextAlphaFactor() {
        AuraIntroConfig cfg = AuraIntroConfigHolder.get();
        return timelineFadeFactor(cfg.timing.textFadeInAtMs, cfg.timing.textFadeInDurationMs);
    }

    /**
     * Fade factor on the video's own timeline, or {@code -1} while vanilla is in charge. The buttons and the
     * texts differ only in the timings they pass in here.
     */
    private float timelineFadeFactor(int startMs, int durationMs) {
        AuraIntroConfig cfg = AuraIntroConfigHolder.get();
        if (!cfg.general.enabled || !cfg.overrideButtonFade() || !holdingEligible() || this.ended) {
            return -1.0F;
        }
        long time = videoTimeMs();
        if (time < startMs) {
            return 0.0F;
        }
        if (durationMs <= 0) {
            return 1.0F;
        }
        return Mth.clamp((time - startMs) / (float) durationMs, 0.0F, 1.0F);
    }

    /**
     * Fade factor from a wall-clock timestamp, for the fades whose start is an event rather than a video
     * time (the wordmark after the video, a badge that appears late).
     */
    private static float fadeSince(long startMs, int durationMs) {
        if (durationMs <= 0 || startMs == 0L) {
            return 1.0F;
        }
        return Mth.clamp((Util.getMillis() - startMs) / (float) durationMs, 0.0F, 1.0F);
    }

    /**
     * Scales the alpha of a title screen text colour to the configured fade, or returns it unchanged when
     * the texts are vanilla's business.
     */
    public int scaleTitleTextAlpha(int colour) {
        float factor = titleTextAlphaFactor();
        if (factor < 0.0F) {
            return colour;
        }
        return ARGB.color(Math.round(ARGB.alpha(colour) * factor),
                ARGB.red(colour), ARGB.green(colour), ARGB.blue(colour));
    }

    /**
     * Alpha for the badge-style widgets: the Realms and Friends icon buttons, and whatever the Realms
     * notification screen draws.
     *
     * <p>Those cannot use the video timestamps like the buttons do. Badges appear when a service answers,
     * and until the video hands the title screen over they are hidden behind the opaque loading overlay -
     * so by the time one becomes visible the configured fade is usually already over, and it pops in. This
     * fades from whichever came last: the widget showing up, or the title screen becoming visible.</p>
     *
     * @param present whether the badge exists this frame
     */
    public float titleBadgeAlpha(boolean present) {
        AuraIntroConfig cfg = AuraIntroConfigHolder.get();
        if (!cfg.general.enabled || !cfg.overrideButtonFade()) {
            return 1.0F;
        }
        if (!this.anchorSet && !this.introPlayedOnce) {
            // Waiting for the hand-over: the badge must not show through the loading overlay's fade, and it
            // starts its own fade from the moment the title screen appears.
            return 0.0F;
        }
        if (this.failed || this.ended) {
            // The intro is over (or never started): the badge is an ordinary badge again. Without this the
            // badge disappeared for good once the video ended, because tearing the session down clears the
            // anchor that the fade is measured from.
            return 1.0F;
        }
        if (!present) {
            this.badgeFirstSeenMs = 0L;
            return 1.0F;
        }
        if (this.badgeFirstSeenMs == 0L) {
            this.badgeFirstSeenMs = Util.getMillis();
        }
        // Badges fade on the buttons' timetable - so a longer button fade-in moves them along - but never
        // before they can actually be seen.
        long from = Math.max(this.badgeFirstSeenMs, this.anchorSetMs);
        from = Math.max(from, this.t0Ms + cfg.timing.buttonsFadeInAtMs);
        return fadeSince(from, cfg.timing.buttonsFadeInDurationMs);
    }

    /**
     * Scales the alpha the splash text is drawn with (vanilla passes its own screen fade, and the splash
     * keeps its pulsing on top of it).
     */
    public float scaleSplashAlpha(float vanillaAlpha) {
        float factor = titleTextAlphaFactor();
        return factor < 0.0F ? vanillaAlpha : vanillaAlpha * factor;
    }

    /**
     * Alpha for the "MINECRAFT" wordmark with {@code general.fadeInAfterVideo}: nothing while the video is on
     * screen, then a fade-in from the moment the video ended. Without that option, or when no video ran at
     * all, the wordmark is left exactly as vanilla draws it.
     */
    public float scaleLogoAlpha(float vanillaAlpha) {
        AuraIntroConfig cfg = AuraIntroConfigHolder.get();
        if (!cfg.general.enabled || !cfg.general.fadeInAfterVideo || this.failed) {
            return vanillaAlpha;
        }
        if (!this.anchorSet && !this.introPlayedOnce) {
            // Waiting for the hand-over.
            return 0.0F;
        }
        if (this.sessionActive && this.endFadeStartMs < 0L && !this.frozen) {
            // Still playing normally. Once it ends - the fade-out starting, or the freeze frame - the
            // wordmark fades in alongside, which is the whole point of this option.
            return 0.0F;
        }
        if (this.logoFadeStartMs == 0L) {
            // No video ran, so there is nothing to wait for.
            return vanillaAlpha;
        }
        return vanillaAlpha * fadeSince(this.logoFadeStartMs, cfg.timing.textFadeInDurationMs);
    }

    /**
     * Alpha the title screen buttons should have, or {@code -1} when vanilla's own fade should
     * be used instead.
     */
    public float buttonAlphaOverride() {
        AuraIntroConfig cfg = AuraIntroConfigHolder.get();
        return timelineFadeFactor(cfg.timing.buttonsFadeInAtMs, cfg.timing.buttonsFadeInDurationMs);
    }

    /** Overall video opacity including fade in/out and the configured opacity multiplier. */
    private float videoAlpha(AuraIntroConfig cfg) {
        long time = videoTimeMs();
        if (time < 0L) {
            return 0.0F;
        }
        float alpha = 1.0F;
        // A baked video is already on screen when the intro starts - it just keeps playing - so fading it
        // in again here would blink the loading scene's own background through for the fade duration.
        if (!isBakedVideo(cfg) && cfg.timing.videoFadeInMs > 0 && time < cfg.timing.videoFadeInMs) {
            alpha = time / (float) cfg.timing.videoFadeInMs;
        }
        if (this.endFadeStartMs > 0L) {
            int duration = cfg.timing.videoFadeOutMs;
            if (duration <= 0) {
                return 0.0F;
            }
            float fadeOut = 1.0F - (Util.getMillis() - this.endFadeStartMs) / (float) duration;
            alpha = Math.min(alpha, fadeOut);
        }
        alpha *= cfg.video.videoOpacity / 100.0F;
        return Mth.clamp(alpha, 0.0F, 1.0F);
    }

    /**
     * True while the vanilla MOJANG STUDIOS logo should stay off the loading screen.
     *
     * <p>That includes the very first frames of a loading screen that is about to show the video,
     * before the video's first frame has arrived: the logo popping in for a frame or two only to be
     * covered by the video a moment later looks like a glitch. {@code loadingLayerRetired} keeps
     * this off for reload splashes that do not replay the video - there the vanilla screen, logo
     * included, is exactly what should show - and lets the logo draw again once the hand-over to
     * the title screen retired the loading scene's layer.</p>
     */
    public boolean shouldHideLoadingLogo() {
        AuraIntroConfig cfg = AuraIntroConfigHolder.get();
        return cfg.general.enabled && cfg.loadingBackground.enabled && cfg.loadingBackground.hideVanillaLogo
                && !this.loadingLayerRetired && !this.backgroundFailed;
    }

    public boolean shouldHideSplashText() {
        return AuraIntroConfigHolder.get().hideSplash();
    }

    /**
     * The factor the title screen widgets are drawn scaled by, or {@code -1} when they should not be
     * scaled at all (mod off, not the title screen, or the configured scale already equals the one
     * the game runs at).
     *
     * <p>The configured value uses vanilla's own GUI scale semantics: an integer, resolved through
     * {@code Window.calculateScale} exactly like the game's "GUI Scale" option does it (which also
     * clamps it to what the window supports, and treats {@code 0} as "Auto"). The returned factor is
     * that scale relative to the scale the rest of the UI runs at, so the buttons end up exactly as
     * vanilla would draw them at that scale - pixel for pixel.</p>
     */
    public float buttonScaleFactorFor(Screen screen) {
        AuraIntroConfig cfg = AuraIntroConfigHolder.get();
        if (!cfg.general.enabled || !(screen instanceof TitleScreen) || cfg.layout.buttonsGuiScale < 0) {
            return -1.0F;
        }
        Window window = Minecraft.getInstance().getWindow();
        int buttonScale = window.calculateScale(cfg.layout.buttonsGuiScale, false);
        float factor = buttonScale / (float) window.getGuiScale();
        return Math.abs(factor - 1.0F) < 1.0E-4F ? -1.0F : factor;
    }

    /** Called after {@code TitleScreen.init()} to push the buttons down/up by the configured amount. */
    public void applyButtonYOffset(Screen screen) {
        AuraIntroConfig cfg = AuraIntroConfigHolder.get();
        if (!cfg.general.enabled || cfg.layout.buttonsYOffset == 0) {
            return;
        }
        for (GuiEventListener child : screen.children()) {
            if (child instanceof AbstractWidget widget) {
                widget.setY(widget.getY() + cfg.layout.buttonsYOffset);
            }
        }
    }

    /**
     * Pushes the pose that draws one title screen widget at vanilla's layout for the configured
     * button GUI scale (call between {@code pushMatrix()} and {@code popMatrix()} on the stack).
     *
     * <p>Vanilla lays the title screen out against fixed anchors, so the transform has to use those
     * same anchors: every widget is centred on {@code width / 2}, the button block starts at
     * {@code height / 4 + 48}, and the copyright line hangs off the bottom edge. Scaling around the
     * screen centre instead (what a naive "GUI scale" transform does) moves the whole block by a
     * quarter of the screen height relative to vanilla's own layout whenever the two scales differ.</p>
     */
    public static void pushButtonScale(Matrix3x2fStack pose, AbstractWidget widget, float factor,
                                       int guiWidth, int guiHeight) {
        float x = widget.getX();
        float y = widget.getY();
        float targetX = guiWidth / 2.0F + (x - guiWidth / 2.0F) * factor;
        float targetY = buttonScaleTargetY(widget, guiHeight, factor);
        pose.translate(targetX, targetY);
        pose.scale(factor, factor);
        pose.translate(-x, -y);
    }

    /**
     * Transforms a mouse coordinate into the coordinate space of the scaled widget, so hit testing
     * keeps matching what the user sees - the exact inverse of {@link #pushButtonScale}.
     */
    public static double[] mouseToButtonSpace(AbstractWidget widget, double x, double y, float factor,
                                              int guiWidth, int guiHeight) {
        double transformedX = guiWidth / 2.0 + (x - guiWidth / 2.0) / factor;
        double transformedY;
        if (widget.getY() + widget.getHeight() >= guiHeight) {
            transformedY = guiHeight - (guiHeight - y) / factor;
        } else {
            transformedY = guiHeight / 4.0 + (y - guiHeight / 4.0) / factor;
        }
        return new double[]{transformedX, transformedY};
    }

    /** Where a widget's top edge belongs at the button GUI scale, using vanilla's own anchor. */
    private static float buttonScaleTargetY(AbstractWidget widget, int guiHeight, float factor) {
        float y = widget.getY();
        if (y + widget.getHeight() >= guiHeight) {
            // Bottom-anchored (the copyright line): its distance to the bottom edge scales.
            return guiHeight - (guiHeight - y) * factor;
        }
        // The button block is anchored at height / 4 (plus fixed offsets), not the centre.
        return guiHeight / 4.0F + (y - guiHeight / 4.0F) * factor;
    }

    // ------------------------------------------------------------------
    // Video layer rendering
    // ------------------------------------------------------------------

    /**
     * Title screen, before its widgets: draws whichever layer belongs to the current scene.
     *
     * <p>Before the hand-over that is the loading scene's layer - the title screen is rendered underneath
     * the loading overlay, so during the overlay's fade-out it is the only thing over the panorama - and
     * afterwards the intro layer. Exactly one layer is drawn, once per frame.</p>
     */
    public void drawTitleScreenVideo(GuiGraphicsExtractor graphics) {
        if (this.sessionActive && this.introVisible) {
            drawIntroVideo(graphics);
            return;
        }
        drawLoadingBackground(graphics);
    }

    /**
     * The intro layer, wherever it is drawn: over the loading overlay while it is held, and on the title
     * screen once the hand-over happened.
     *
     * <p>A baked video composites the same way in both scenes (it was the loading scene's background, and
     * its alpha is dropped by the player's frame sink), so the hand-over is not a visible switch.</p>
     */
    public void drawIntroVideo(GuiGraphicsExtractor graphics) {
        guard("drawing the intro video", () -> drawIntroVideoNow(graphics));
    }

    private void drawIntroVideoNow(GuiGraphicsExtractor graphics) {
        AuraIntroConfig cfg = AuraIntroConfigHolder.get();
        if (!cfg.general.enabled || this.failed || this.ended || !this.sessionActive
                || !this.introVisible || graphics == null) {
            if (cfg.general.debugLogging && this.anchorSet && !this.layerSkipReported) {
                this.layerSkipReported = true;
                LOGGER.warn("Intro video not drawn on the title screen (enabled={} failed={} ended={} "
                                + "session={} visible={}) - the panorama and buttons behind it will be "
                                + "visible",
                        cfg.general.enabled, this.failed, this.ended, this.sessionActive, this.introVisible);
            }
            return;
        }

        this.uploadIntroFrame(cfg, cfg.video.videoMaxFps);

        float alpha = videoAlpha(cfg);
        if (alpha <= 0.004F) {
            return;
        }
        traceHandOver("title draw", "alpha=" + alpha + " introFramesUploaded=" + this.introFramesUploaded
                + " overlayPresent=" + (McCompat.currentOverlay(Minecraft.getInstance()) != null));
        this.introTexture.draw(graphics, this.player, cfg.video.videoFit, alpha);
    }

    /**
     * Uploads the newest frame to the intro layer and, on the first one, fixes the intro timeline.
     *
     * <p>The config timings are relative to the video, so the timeline only starts once the video's first
     * frame is on screen - not when the decoder was asked to start it (that takes a moment, which used to
     * shift every timing).</p>
     *
     * @return {@code true} when a frame reached the texture
     */
    private boolean uploadIntroFrame(AuraIntroConfig cfg, int maxFps) {
        VideoFrameSink sink = this.player.sink();
        if (sink.width() <= 0 || sink.height() <= 0) {
            return false;
        }
        boolean uploaded = this.introTexture.upload(this.player, maxFps);
        if (uploaded) {
            this.introFramesUploaded++;
            if (!this.timelineLocked) {
                this.timelineLocked = true;
                this.t0Ms = Util.getMillis() + cfg.timing.videoStartDelayMs;
                if (cfg.general.debugLogging) {
                    LOGGER.info("Video intro timeline starts now (first frame on screen; all intro timings "
                            + "are measured from here)");
                }
            }
        }
        if (!this.textureFormatLogged) {
            this.textureFormatLogged = true;
            if (cfg.general.debugLogging) {
                LOGGER.info("Video buffer {}x{}, visible picture {}x{}", sink.width(), sink.height(),
                        sink.visibleWidth(), sink.visibleHeight());
            }
        }
        return uploaded;
    }

    private static boolean isLoadReady(LoadingOverlay overlay) {
        LoadingOverlayAccessor accessor = (LoadingOverlayAccessor) overlay;
        ReloadInstance reload = accessor.getReload();
        if (reload == null || !reload.isDone()) {
            return false;
        }
        if (!accessor.isFadeIn()) {
            return true;
        }
        long fadeInStart = accessor.getFadeInStart();
        return fadeInStart > -1L && Util.getMillis() - fadeInStart >= 1000L;
    }
}
