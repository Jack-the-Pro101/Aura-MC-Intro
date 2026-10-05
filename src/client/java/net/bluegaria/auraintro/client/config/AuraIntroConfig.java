package net.bluegaria.auraintro.client.config;

import com.google.gson.annotations.SerializedName;
import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.annotation.ConfigEntry;
import net.bluegaria.auraintro.client.compat.Platform;

import java.nio.file.Path;

/**
 * All configuration for the title screen video intro.
 *
 * <p>Everything related to timing is expressed in milliseconds and - unless explicitly stated
 * otherwise - is relative to the moment the intro video starts playing (video timestamp 0).
 * Negative values are clamped to 0 so a broken config can never lock the game up.</p>
 */
@Config(name = "aura-intro")
public class AuraIntroConfig implements ConfigData {

    public enum VideoFit {
        /** Scale the video so it fills the whole screen, cropping whatever does not fit. */
        COVER,
        /** Scale the video so it is fully visible, letterboxing where needed. */
        CONTAIN,
        /** Stretch the video to the full screen, ignoring the aspect ratio. */
        STRETCH
    }

    public enum EndBehaviour {
        /** Keep looping the configured loop region (or the whole video) forever. */
        LOOP_REGION,
        /** Fade the video out and reveal the vanilla panorama title screen. */
        FADE_OUT_TO_PANORAMA,
        /** Freeze on the last decoded frame when the video ends. */
        FREEZE_LAST_FRAME
    }

    public enum OverlayUnloadStyle {
        /** Remove the loading overlay (MOJANG logo) instantly at the configured timestamp. */
        INSTANT,
        /**
         * Let vanilla run its own two second fade after the configured timestamp; the video is
         * drawn on top of that transition.
         */
        VANILLA_FADE
    }

    @ConfigEntry.Gui.CollapsibleObject
    public General general = new General();

    @ConfigEntry.Gui.CollapsibleObject
    public Video video = new Video();

    @ConfigEntry.Gui.CollapsibleObject
    public Timing timing = new Timing();

    @ConfigEntry.Gui.CollapsibleObject
    public Layout layout = new Layout();

    @ConfigEntry.Gui.CollapsibleObject
    public LoadingBackground loadingBackground = new LoadingBackground();

    public static class General {
        @ConfigEntry.Gui.Tooltip
        public boolean enabled = true;

        /**
         * Keep the "MINECRAFT" wordmark above the title screen buttons hidden while the video plays, and fade
         * it in once the video has ended. Off leaves the wordmark exactly as vanilla draws it.
         */
        @ConfigEntry.Gui.Tooltip
        public boolean fadeInAfterVideo = true;

        /**
         * With {@link #fadeInAfterVideo} and a loop region: a loop never ends, so the wordmark would stay
         * hidden for good. On fades it in the first time the video loops instead (when the music takes
         * over). Off keeps it hidden while the loop plays - for a video with its own logo.
         */
        @ConfigEntry.Gui.Tooltip
        public boolean wordmarkAfterFirstLoop = false;

        /** Hides the rotating yellow splash text as well. */
        @ConfigEntry.Gui.Tooltip
        public boolean hideSplashText = false;

        /** Writes detailed video state changes to the log. Useful when tuning timings. */
        @ConfigEntry.Gui.Tooltip
        public boolean debugLogging = false;

        /**
         * Keep vanilla's menu music quiet while the video is playing, so it cannot talk over the
         * video's own soundtrack. The music returns when the video has ended.
         */
        @ConfigEntry.Gui.Tooltip
        public boolean suppressMenuMusic = true;

        /**
         * Replays the whole video intro (loading screen included) every time the resource loading
         * splash appears again (for example after F3+T or when changing resource packs). Disabled
         * by default so the intro only plays once at startup.
         */
        @ConfigEntry.Gui.Tooltip
        public boolean replayOnResourceReload = false;

        /**
         * Play the video that ships inside the jar when the configured file is not there - so the mod works
         * with no setup at all, and a file at the configured path takes over whenever there is one.
         */
        @ConfigEntry.Gui.Tooltip
        public boolean useBundledDefaultVideo = true;
    }

    public static class Video {
        /**
         * Path to the intro video, relative to the Minecraft game directory (or an absolute path). When a
         * file is there it is used; otherwise the mod's own bundled video plays. A .webm with VP9 video
         * and an Opus audio track is recommended.
         */
        @ConfigEntry.Gui.Tooltip
        public String videoPath = "config/aura-intro/intro.webm";

        /** Playback volume, 0-100. 0 silences the video (useful for a silent intro). */
        @ConfigEntry.Gui.Tooltip
        @ConfigEntry.BoundedDiscrete(min = 0, max = 100)
        public int videoVolume = 100;

        /**
         * Play a baked video's sound in the video player instead of a separate audio-only player.
         *
         * <p>On by default: one player has one clock, so picture and sound cannot drift apart and no delay is
         * needed, and the decoder keeps the sound on time by dropping late video frames. The alternative - a
         * separate audio player - is immune to a slow video pipeline, but then the two clocks have to be
         * lined up by hand with {@link #audioDelayMs}.</p>
         */
        @ConfigEntry.Gui.Tooltip
        public boolean audioInSamePlayer = true;

        /**
         * Shifts the sound of a baked video relative to its picture, in milliseconds. Positive plays the
         * sound later. Only used when {@link #audioInSamePlayer} is off: with one player for both streams
         * they share a clock, and with a separate audio player this is what lines them up (the picture
         * arrives a few frames late at 4K while the sound does not).
         */
        @ConfigEntry.Gui.Tooltip
        @ConfigEntry.BoundedDiscrete(min = -2000, max = 2000)
        public int audioDelayMs = 0;

        /**
         * Fine-tuning of the A/V sync, in milliseconds, on top of the output latency the player
         * measures itself (on the Linux sound server path): positive delays the picture further,
         * negative brings it forward. Meant for latency no output reports honestly.
         */
        @ConfigEntry.Gui.Tooltip
        @ConfigEntry.BoundedDiscrete(min = -1000, max = 2000)
        public int audioLatencyMs = 0;

        /**
         * Output device for the video's sound. On Linux, where the sound plays through the system
         * sound server (the same route as the game's own sound), this is a sink name as shown by
         * the desktop's audio widget (e.g. {@code WH-CH520}); when the sound server cannot be used
         * it is a Java Sound mixer name instead (the log lists them). Empty follows the system's
         * default output.
         */
        @ConfigEntry.Gui.Tooltip
        public String videoAudioDevice = "";

        /** Extra opacity multiplier applied on top of the video's alpha channel, 0-100. */
        @ConfigEntry.Gui.Tooltip
        @ConfigEntry.BoundedDiscrete(min = 0, max = 100)
        public int videoOpacity = 100;

        @ConfigEntry.Gui.Tooltip
        @ConfigEntry.Gui.EnumHandler(option = ConfigEntry.Gui.EnumHandler.EnumDisplayOption.BUTTON)
        public VideoFit videoFit = VideoFit.COVER;

        /** Upper bound for how often decoded frames are uploaded to the GPU. 0 = unlimited. */
        @ConfigEntry.Gui.Tooltip
        @ConfigEntry.BoundedDiscrete(min = 0, max = 240)
        public int videoMaxFps = 60;

        /**
         * Decode the video on the GPU where the platform provides it (D3D11VA on Windows,
         * VideoToolbox on macOS, VAAPI/NVDEC on Linux), falling back to software decoding
         * automatically when no working hardware decoder is found.
         */
        @ConfigEntry.Gui.Tooltip
        public boolean hardwareDecoding = true;
    }

    public static class Timing {
        /**
         * How long the video takes to fade in once it starts, in milliseconds. Only used when the
         * loading screen does <em>not</em> show the video (with the video already on screen behind
         * the loading bar it just keeps playing, so fading it in again would blink the loading
         * screen through).
         */
        @ConfigEntry.Gui.Tooltip
        @ConfigEntry.BoundedDiscrete(min = 0, max = 20000)
        public int videoFadeInMs = 0;

        /**
         * Video timestamp at which the vanilla progress bar starts fading away. The Mojang
         * logo keeps being rendered until the loading overlay is unloaded.
         */
        @ConfigEntry.Gui.Tooltip
        @ConfigEntry.BoundedDiscrete(min = 0, max = 60000)
        public int progressBarFadeStartMs = 200;

        /** How long the progress bar takes to disappear once it starts fading, in milliseconds. */
        @ConfigEntry.Gui.Tooltip
        @ConfigEntry.BoundedDiscrete(min = 0, max = 60000)
        public int progressBarFadeDurationMs = 800;

        /**
         * Video timestamp at which the vanilla loading overlay (Mojang logo) is unloaded and the
         * title screen underneath becomes visible. Set to 0 to skip holding the overlay entirely
         * and let vanilla handle the transition.
         */
        @ConfigEntry.Gui.Tooltip
        @ConfigEntry.BoundedDiscrete(min = 0, max = 60000)
        public int overlayUnloadAtMs = 1400;

        /**
         * How the overlay is unloaded: instantly (default) or by letting vanilla run its own
         * two second cross-fade after the timestamp above.
         */
        @ConfigEntry.Gui.Tooltip
        @ConfigEntry.Gui.EnumHandler(option = ConfigEntry.Gui.EnumHandler.EnumDisplayOption.BUTTON)
        public OverlayUnloadStyle overlayUnloadStyle = OverlayUnloadStyle.INSTANT;

        /** Video timestamp at which the title screen buttons start fading in. */
        @ConfigEntry.Gui.Tooltip
        @ConfigEntry.BoundedDiscrete(min = 0, max = 60000)
        public int buttonsFadeInAtMs = 2150;

        /** How long the buttons take to fade in, in milliseconds. 0 makes them appear instantly. */
        @ConfigEntry.Gui.Tooltip
        @ConfigEntry.BoundedDiscrete(min = 0, max = 60000)
        public int buttonsFadeInDurationMs = 1000;

        /**
         * Video timestamp at which the title screen's texts (version/mod count and splash) and the Realms
         * badge start fading in. They are fully transparent before it, like the buttons.
         */
        @ConfigEntry.Gui.Tooltip
        @ConfigEntry.BoundedDiscrete(min = 0, max = 60000)
        public int textFadeInAtMs = 2800;

        /** How long those texts take to fade in, in milliseconds. 0 makes them appear instantly. */
        @ConfigEntry.Gui.Tooltip
        @ConfigEntry.BoundedDiscrete(min = 0, max = 60000)
        public int textFadeInDurationMs = 1000;

        /** Fade duration used by {@link EndBehaviour#FADE_OUT_TO_PANORAMA}. */
        @ConfigEntry.Gui.Tooltip
        @ConfigEntry.BoundedDiscrete(min = 0, max = 60000)
        public int videoFadeOutMs = 1200;

        @ConfigEntry.Gui.Tooltip
        @ConfigEntry.Gui.EnumHandler(option = ConfigEntry.Gui.EnumHandler.EnumDisplayOption.BUTTON)
        public EndBehaviour endBehaviour = EndBehaviour.FADE_OUT_TO_PANORAMA;

        /** Loop region start, in milliseconds. Only used by {@link EndBehaviour#LOOP_REGION}. */
        @ConfigEntry.Gui.Tooltip
        @ConfigEntry.BoundedDiscrete(min = 0, max = 600000)
        public int loopStartMs = 7480;

        /**
         * Loop region end, in milliseconds. 0 means "end of the video". Only used by
         * {@link EndBehaviour#LOOP_REGION}.
         */
        @ConfigEntry.Gui.Tooltip
        @ConfigEntry.BoundedDiscrete(min = 0, max = 600000)
        public int loopEndMs = 16280;

        /**
         * Keeps the loop region as the title screen's background for the rest of the session: leaving
         * the title screen (another menu, a world) pauses the loop instead of ending it, and it carries
         * on when the title screen comes back - the panorama never takes over. Only used by
         * {@link EndBehaviour#LOOP_REGION}; the video's sound stays muted and the menu music plays.
         */
        @ConfigEntry.Gui.Tooltip
        public boolean persistLoopAsBackground = false;

        /**
         * With {@link #persistLoopAsBackground}: the loop also keeps playing behind the other menus
         * (options, world select, ...) - blurred, where vanilla shows its panorama - instead of pausing
         * until the title screen is back. In a world it pauses either way.
         */
        @ConfigEntry.Gui.Tooltip
        public boolean loopInOtherMenus = false;
    }

    public static class Layout {
        /**
         * Moves all title screen buttons down by this many GUI-scale pixels. Negative values
         * move them up. 0 keeps the vanilla position.
         */
        @ConfigEntry.Gui.Tooltip
        @ConfigEntry.BoundedDiscrete(min = -400, max = 400)
        public int buttonsYOffset = 0;

        /**
         * GUI scale applied to the whole title screen (buttons, wordmark, splash, version and
         * copyright lines, Realms badge), independent of the game's own GUI scale - with exactly
         * vanilla's semantics: an integer scale that is clamped to what the
         * window supports, just like the game's "GUI Scale" option. -1 (default) follows the
         * regular GUI scale, 0 means "Auto" (the largest scale the window supports) and 1-4 are
         * fixed scales.
         */
        @ConfigEntry.Gui.Tooltip
        @ConfigEntry.BoundedDiscrete(min = -1, max = 4)
        public int buttonsGuiScale = 0;

        /**
         * When enabled, the vanilla 2 second button fade-in is left untouched and the
         * "buttons fade in" timings above are ignored.
         */
        @ConfigEntry.Gui.Tooltip
        public boolean useVanillaButtonFade = false;
    }

    /**
     * How the (single) intro video is used behind the vanilla "MOJANG STUDIOS" loading screen
     * (behind the loading bar): it is drawn there from the moment the window appears, freezes on
     * the {@link #holdAtMs} frame while the game finishes loading and then continues into the
     * intro - one player, one decoder, no hand-over.
     */
    public static class LoadingBackground {
        /**
         * Draw the intro video behind the vanilla loading bar instead of the plain red background.
         * When off, the loading screen stays vanilla and the video intro only starts (and fades in)
         * once loading has finished.
         */
        @ConfigEntry.Gui.Tooltip
        public boolean enabled = true;

        /**
         * Fade-in duration once the loading screen appears, in milliseconds. 0 (the default) cuts
         * straight to the video's first frame; the plain loading background shows only for the
         * moments before that frame was decoded.
         */
        @ConfigEntry.Gui.Tooltip
        @ConfigEntry.BoundedDiscrete(min = 0, max = 20000)
        public int fadeInMs = 0;

        /**
         * Hide the vanilla "MOJANG STUDIOS" logo while the video is showing, so the
         * loading bar sits directly on top of the video.
         */
        @ConfigEntry.Gui.Tooltip
        public boolean hideVanillaLogo = true;

        /**
         * When enabled the loading screen is kept up until the video has reached the freeze frame
         * ({@link #holdAtMs}) - or its end, with {@code holdAtMs} at 0 - so the intro never starts
         * from a frame the loading scene has not shown yet.
         *
         * <p>Called {@code waitForVideoToFinish} before; configs that still use that name are read too.</p>
         */
        @ConfigEntry.Gui.Tooltip
        @SerializedName(value = "waitUntilHoldMsReached", alternate = "waitForVideoToFinish")
        public boolean waitUntilHoldMsReached = true;

        /**
         * Safety net for the option above: give up waiting after this many milliseconds so a
         * stalled video can never block loading. 0 = wait forever.
         */
        @ConfigEntry.Gui.Tooltip
        @ConfigEntry.BoundedDiscrete(min = 0, max = 600000)
        public int maxWaitForVideoMs = 30000;

        /**
         * Timestamp (ms) in the video at which the loading screen freezes on a frame and waits for
         * the game to finish loading; the intro then continues from exactly that frame. 0 plays the
         * clip through during loading instead.
         */
        @ConfigEntry.Gui.Tooltip
        @ConfigEntry.BoundedDiscrete(min = 0, max = 600000)
        public int holdAtMs = 3400;
    }

    // ------------------------------------------------------------------
    // Helpers used by the runtime
    // ------------------------------------------------------------------

    /**
     * Path of the video: the configured file, resolved against the game directory (or absolute).
     */
    public Path resolveVideoPath() {
        String configured = this.video.videoPath == null ? "" : this.video.videoPath.trim();
        if (configured.isEmpty()) {
            return null;
        }
        Path path = Path.of(configured);
        if (!path.isAbsolute()) {
            path = Platform.gameDir().resolve(path);
        }
        return path.normalize();
    }

    public boolean hideSplash() {
        return this.general.enabled && this.general.hideSplashText;
    }

    public boolean overrideButtonFade() {
        return this.general.enabled && !this.layout.useVanillaButtonFade;
    }

    @Override
    public void validatePostLoad() throws ValidationException {
        if (this.general == null) {
            this.general = new General();
        }
        if (this.video == null) {
            this.video = new Video();
        }
        if (this.timing == null) {
            this.timing = new Timing();
        }
        if (this.layout == null) {
            this.layout = new Layout();
        }
        if (this.loadingBackground == null) {
            this.loadingBackground = new LoadingBackground();
        }
        if (this.video.videoFit == null) {
            this.video.videoFit = VideoFit.COVER;
        }
        if (this.timing.endBehaviour == null) {
            this.timing.endBehaviour = EndBehaviour.FADE_OUT_TO_PANORAMA;
        }
        if (this.timing.overlayUnloadStyle == null) {
            this.timing.overlayUnloadStyle = OverlayUnloadStyle.INSTANT;
        }

        this.video.videoVolume = clamp(this.video.videoVolume, 0, 100);
        this.video.videoOpacity = clamp(this.video.videoOpacity, 0, 100);
        this.video.videoMaxFps = clamp(this.video.videoMaxFps, 0, 240);
        this.video.audioDelayMs = clamp(this.video.audioDelayMs, -2000, 2000);
        this.video.audioLatencyMs = clamp(this.video.audioLatencyMs, -1000, 2000);

        Timing t = this.timing;
        t.videoFadeInMs = Math.max(0, t.videoFadeInMs);
        t.progressBarFadeStartMs = Math.max(0, t.progressBarFadeStartMs);
        t.progressBarFadeDurationMs = Math.max(0, t.progressBarFadeDurationMs);
        t.overlayUnloadAtMs = Math.max(0, t.overlayUnloadAtMs);
        t.buttonsFadeInAtMs = Math.max(0, t.buttonsFadeInAtMs);
        t.buttonsFadeInDurationMs = Math.max(0, t.buttonsFadeInDurationMs);
        t.videoFadeOutMs = Math.max(0, t.videoFadeOutMs);
        t.textFadeInAtMs = Math.max(0, t.textFadeInAtMs);
        t.textFadeInDurationMs = Math.max(0, t.textFadeInDurationMs);
        t.loopStartMs = Math.max(0, t.loopStartMs);
        t.loopEndMs = Math.max(0, t.loopEndMs);
        if (t.loopEndMs > 0 && t.loopEndMs <= t.loopStartMs) {
            // An empty/backwards loop region would seek every frame; treat it as "to the end".
            t.loopEndMs = 0;
        }

        this.layout.buttonsYOffset = clamp(this.layout.buttonsYOffset, -400, 400);
        this.layout.buttonsGuiScale = clamp(this.layout.buttonsGuiScale, -1, 4);

        this.loadingBackground.fadeInMs = Math.max(0, this.loadingBackground.fadeInMs);
        this.loadingBackground.holdAtMs = Math.max(0, this.loadingBackground.holdAtMs);
        this.loadingBackground.maxWaitForVideoMs = clamp(this.loadingBackground.maxWaitForVideoMs, 0, 600000);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
