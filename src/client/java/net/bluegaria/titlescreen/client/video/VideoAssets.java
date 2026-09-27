package net.bluegaria.titlescreen.client.video;

import net.bluegaria.titlescreen.client.config.TitlescreenConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The video files the mod ships with.
 *
 * <p>Both videos are placeholders that make the mod do something sensible out of the box: the configured
 * path is simply replaced with a real video whenever the player wants to. Extraction happens lazily,
 * exactly when a configured file turns out to be missing.</p>
 */
public final class VideoAssets {

    private static final Logger LOGGER = LoggerFactory.getLogger("Titlescreen/Video");

    private static final String INTRO = "/assets/titlescreen/video/default_intro.webm";
    private static final String LOADING_BACKGROUND = "/assets/titlescreen/video/mojang_studios.webm";

    private VideoAssets() {
    }

    /**
     * Makes sure the configured intro video exists, extracting the bundled placeholder when the config
     * allows it.
     *
     * @return {@code true} when the file is there afterwards
     */
    public static boolean ensureIntro(TitlescreenConfig cfg, Path target) {
        return cfg.general.useBundledDefaultVideo && extract(target, INTRO);
    }

    /** Same as {@link #ensureIntro}, for the separate loading background video. */
    public static boolean ensureLoadingBackground(TitlescreenConfig cfg, Path target) {
        return cfg.loadingBackground.useBundledDefaultVideo && extract(target, LOADING_BACKGROUND);
    }

    private static boolean extract(Path target, String resource) {
        try (InputStream in = VideoAssets.class.getResourceAsStream(resource)) {
            if (in == null) {
                LOGGER.warn("The bundled video ({}) is missing from the mod jar", resource);
                return false;
            }
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("Copied the bundled video {} to {} - replace it with your own video any time",
                    resource, target);
            return true;
        } catch (IOException e) {
            LOGGER.warn("Could not extract the bundled video {} to {}", resource, target, e);
            return false;
        }
    }
}
