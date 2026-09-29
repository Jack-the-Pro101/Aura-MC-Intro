package net.bluegaria.titlescreen.client.config;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.annotation.ConfigEntry;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

/**
 * All configuration for the title screen video intro.
 *
 * <p>Everything related to timing is expressed in milliseconds and - unless explicitly stated
 * otherwise - is relative to the moment the intro video starts playing (video timestamp 0).
 * Negative values are clamped to 0 so a broken config can never lock the game up.</p>
 */
@Config(name = "titlescreen")
public class TitlescreenConfig implements ConfigData {

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
         * Replays the intro every time the resource loading splash appears again (for example
         * after F3+T or when changing resource packs). Disabled by default so the intro only
         * plays once at startup.
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
         * and an Opus/Vorbis audio track is recommended.
         */
        @ConfigEntry.Gui.Tooltip
        public String videoPath = "config/titlescreen/intro.webm";

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
    }

    public static class Timing {
        /** Delay between "loading finished" and the first video frame, in milliseconds. */
        @ConfigEntry.Gui.Tooltip
        @ConfigEntry.BoundedDiscrete(min = 0, max = 20000)
        public int videoStartDelayMs = 0;

        /**
         * How long the video takes to fade in once it starts, in milliseconds. Only used when the loading
         * screen does <em>not</em> reuse the intro video (a baked video is already on screen and just keeps
         * playing, so fading it in again would blink the loading screen through).
         */
        @ConfigEntry.Gui.Tooltip
        @ConfigEntry.BoundedDiscrete(min = 0, max = 20000)
        public int videoFadeInMs = 400;

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
        public int buttonsFadeInAtMs = 2000;

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
        public int textFadeInAtMs = 2700;

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
        public int loopStartMs = 0;

        /**
         * Loop region end, in milliseconds. 0 means "end of the video". Only used by
         * {@link EndBehaviour#LOOP_REGION}.
         */
        @ConfigEntry.Gui.Tooltip
        @ConfigEntry.BoundedDiscrete(min = 0, max = 600000)
        public int loopEndMs = 0;
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
         * GUI scale applied only to the title screen buttons, independent of the vanilla GUI
         * scale. Use -1 (default) to follow the regular GUI scale.
         */
        @ConfigEntry.Gui.Tooltip
        public float buttonsGuiScale = -1.0F;

        /**
         * When enabled, the vanilla 2 second button fade-in is left untouched and the
         * "buttons fade in" timings above are ignored.
         */
        @ConfigEntry.Gui.Tooltip
        public boolean useVanillaButtonFade = false;
    }

    /**
     * A second video that is drawn as the background of the vanilla "MOJANG STUDIOS" loading screen
     * (behind the loading bar). By default it holds its last frame once it ends, so it works as a
     * static background for the whole loading phase. The Minecraft Dungeons loading screen loop is
     * a good fit for this.
     */
    public static class LoadingBackground {
        @ConfigEntry.Gui.Tooltip
        public boolean enabled = true;

        /**
         * Path to the loading background video, relative to the game directory (or absolute). When a file
         * is there it is used; otherwise the loading clip bundled with the mod plays.
         *
         * <p>Only used when "reuse the intro video" below is off: with one baked video the loading scene
         * plays the beginning of that same file.</p>
         */
        @ConfigEntry.Gui.Tooltip
        public String videoPath = "config/titlescreen/loading_background.webm";

        /**
         * Play the loading screen clip bundled with the mod when the configured file is not there. Only used
         * when "reuse the intro video" below is off.
         */
        @ConfigEntry.Gui.Tooltip
        public boolean useBundledDefaultVideo = true;

        /**
         * Volume for this video, 0-100. Only used when "reuse the intro video" below is off: with a baked
         * video the loading scene plays part of the intro, so the intro's volume applies to the whole clip.
         */
        @ConfigEntry.Gui.Tooltip
        @ConfigEntry.BoundedDiscrete(min = 0, max = 100)
        public int volume = 0;

        /** Extra opacity multiplier on top of the video's alpha, 0-100. */
        @ConfigEntry.Gui.Tooltip
        @ConfigEntry.BoundedDiscrete(min = 0, max = 100)
        public int opacity = 100;

        @ConfigEntry.Gui.Tooltip
        @ConfigEntry.Gui.EnumHandler(option = ConfigEntry.Gui.EnumHandler.EnumDisplayOption.BUTTON)
        public VideoFit fit = VideoFit.COVER;

        /** Fade-in duration once the loading screen appears, in milliseconds. */
        @ConfigEntry.Gui.Tooltip
        @ConfigEntry.BoundedDiscrete(min = 0, max = 20000)
        public int fadeInMs = 500;

        /** Upper bound for texture uploads per second while the loading screen is up. 0 = unlimited. */
        @ConfigEntry.Gui.Tooltip
        @ConfigEntry.BoundedDiscrete(min = 0, max = 240)
        public int maxFps = 30;


        /**
         * Hide the vanilla "MOJANG STUDIOS" logo while the background video is showing, so the
         * loading bar sits directly on top of the video.
         */
        @ConfigEntry.Gui.Tooltip
        public boolean hideVanillaLogo = true;

        /**
         * When enabled the video keeps looping instead of holding its last frame. Off by default,
         * because holding the last frame gives a static background for the loading bar.
         */
        @ConfigEntry.Gui.Tooltip
        public boolean loop = false;

        /**
         * When enabled the loading screen is kept up until the background video has played to the
         * end, so the next scene (the intro video / title screen) never starts mid-clip.
         */
        @ConfigEntry.Gui.Tooltip
        public boolean waitForVideoToFinish = true;

        /**
         * Safety net for the option above: give up waiting after this many milliseconds so a
         * broken/looping video can never block loading. 0 = wait forever.
         */
        @ConfigEntry.Gui.Tooltip
        @ConfigEntry.BoundedDiscrete(min = 0, max = 600000)
        public int maxWaitForVideoMs = 30000;

        /** Replay the loading background on resource reload splashes (F3+T, resource packs). */
        @ConfigEntry.Gui.Tooltip
        public boolean replayOnResourceReload = true;

        /**
         * Use the intro video for the loading screen too: one file that contains both scenes, the
         * Mojang/loading part first and the intro after it. This is the recommended setup and the default;
         * it means one player, one decoder and no hand-over at all. Turn it off to use the separate file
         * above for the loading screen.
         */
        @ConfigEntry.Gui.Tooltip
        public boolean useIntroVideo = true;

        /**
         * Timestamp (ms) in the baked video at which the loading screen freezes on a frame and waits for
         * the game to finish loading; the intro then continues from exactly that frame. 0 plays the whole
         * clip during loading instead.
         */
        @ConfigEntry.Gui.Tooltip
        @ConfigEntry.BoundedDiscrete(min = 0, max = 600000)
        public int holdAtMs = 0;
    }

    // ------------------------------------------------------------------
    // Helpers used by the runtime
    // ------------------------------------------------------------------

    public Path resolveVideoPath() {
        String configured = this.video.videoPath == null ? "" : this.video.videoPath.trim();
        if (configured.isEmpty()) {
            return null;
        }
        Path path = Path.of(configured);
        if (!path.isAbsolute()) {
            path = FabricLoader.getInstance().getGameDir().resolve(path);
        }
        return path.normalize();
    }

    /**
     * Path of the loading scene's video: the intro video when it is baked into the loading scene (the
     * default), otherwise the separate loading background file.
     */
    public Path resolveLoadingBackgroundPath() {
        if (this.loadingBackground.enabled && this.loadingBackground.useIntroVideo) {
            // Single baked video: the loading scene shows the beginning of the intro video.
            return resolveVideoPath();
        }
        String configured = this.loadingBackground.videoPath == null
                ? "" : this.loadingBackground.videoPath.trim();
        if (configured.isEmpty()) {
            return null;
        }
        Path path = Path.of(configured);
        if (!path.isAbsolute()) {
            path = FabricLoader.getInstance().getGameDir().resolve(path);
        }
        return path.normalize();
    }

    public boolean hideSplash() {
        return this.general.enabled && this.general.hideSplashText;
    }

    public boolean overrideButtonFade() {
        return this.general.enabled && !this.layout.useVanillaButtonFade;
    }

    public float buttonsGuiScaleOrDefault(float vanillaScale) {
        float configured = this.layout.buttonsGuiScale;
        if (configured <= 0.0F) {
            return vanillaScale;
        }
        return Math.max(0.1F, configured);
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

        Timing t = this.timing;
        t.videoStartDelayMs = Math.max(0, t.videoStartDelayMs);
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
        if (this.layout.buttonsGuiScale != -1.0F) {
            this.layout.buttonsGuiScale = Math.max(0.1F, this.layout.buttonsGuiScale);
        }

        if (this.loadingBackground.fit == null) {
            this.loadingBackground.fit = VideoFit.COVER;
        }
        this.loadingBackground.volume = clamp(this.loadingBackground.volume, 0, 100);
        this.loadingBackground.opacity = clamp(this.loadingBackground.opacity, 0, 100);
        this.loadingBackground.maxFps = clamp(this.loadingBackground.maxFps, 0, 240);
        this.loadingBackground.maxWaitForVideoMs = clamp(this.loadingBackground.maxWaitForVideoMs, 0, 600000);
        this.loadingBackground.fadeInMs = Math.max(0, this.loadingBackground.fadeInMs);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
