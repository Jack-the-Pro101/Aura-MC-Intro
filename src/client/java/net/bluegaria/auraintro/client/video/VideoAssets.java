package net.bluegaria.auraintro.client.video;

import net.bluegaria.auraintro.client.config.AuraIntroConfig;
import net.bluegaria.auraintro.client.compat.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Turns a configured video path into a video file the backend can play.
 *
 * <p>The mod ships with its video, so the video intro works with no setup at all. A configured path only
 * takes over when a file is actually there, which is what makes "drop your own clip at that path" the way to
 * replace it.</p>
 */
public final class VideoAssets {

    private static final Logger LOGGER = LoggerFactory.getLogger("Aura-Intro/Video");

    private static final String BUNDLED_VIDEO = "/assets/aura-intro/video/default_intro.webm";

    /**
     * Where a bundled clip is unpacked for the decoder, which opens files by real path - not something inside
     * a jar. Kept in the config directory so it is obvious what the file is, and reused between launches.
     */
    private static final String CACHE_DIRECTORY = "aura-intro/bundled";

    private VideoAssets() {
    }

    /**
     * The one video of the mod: the configured file when it exists, the bundled clip otherwise.
     * It serves both the loading screen background and the intro - one file, one player.
     *
     * @return the file to play, or {@code null} when neither is available
     */
    public static Path resolveVideo(AuraIntroConfig cfg) {
        return resolve(cfg.resolveVideoPath(), cfg.video.videoPath,
                cfg.general.useBundledDefaultVideo, BUNDLED_VIDEO, "intro.webm");
    }

    private static Path resolve(Path configured, String configuredName, boolean bundledAllowed,
                                String resource, String fileName) {
        if (configured != null && Files.isRegularFile(configured)) {
            return configured;
        }
        if (!bundledAllowed) {
            LOGGER.warn("Video file '{}' was not found and the bundled video is disabled - the video is "
                    + "skipped", configuredName);
            return null;
        }
        if (configured != null) {
            LOGGER.debug("'{}' is not there - using the video bundled with the mod (put a file at that path "
                    + "to override it)", configuredName);
        }
        return unpackBundled(resource, fileName);
    }

    /**
     * Unpacks a clip that ships inside the jar, unless the previously unpacked copy is already the same size
     * (which is how a newer mod version with a different clip replaces it).
     *
     * @return the unpacked file, or {@code null} when it could not be written
     */
    private static Path unpackBundled(String resource, String fileName) {
        Path target = Platform.configDir().resolve(CACHE_DIRECTORY).resolve(fileName);
        try {
            long bundledSize = bundledSize(resource);
            if (Files.isRegularFile(target) && (bundledSize <= 0L || Files.size(target) == bundledSize)) {
                return target;
            }
            try (InputStream in = VideoAssets.class.getResourceAsStream(resource)) {
                if (in == null) {
                    LOGGER.warn("The bundled video ({}) is missing from the mod jar", resource);
                    return null;
                }
                Path parent = target.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                // Through a temporary file: a reader (the decoder of another scene) must never
                // open a half-written clip.
                Path temporary = target.resolveSibling(fileName + ".part");
                Files.copy(in, temporary, StandardCopyOption.REPLACE_EXISTING);
                try {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
                LOGGER.info("Unpacked the bundled video to {} - put your own clip in the configured path to "
                        + "use it instead", target);
                return target;
            }
        } catch (IOException e) {
            LOGGER.warn("Could not unpack the bundled video {}", resource, e);
            return null;
        }
    }

    /** Size of a bundled clip, or {@code -1} when it cannot be read. */
    private static long bundledSize(String resource) {
        URL url = VideoAssets.class.getResource(resource);
        if (url == null) {
            return -1L;
        }
        try {
            return url.openConnection().getContentLengthLong();
        } catch (IOException e) {
            return -1L;
        }
    }
}