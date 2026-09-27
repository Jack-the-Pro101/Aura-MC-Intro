package net.bluegaria.titlescreen.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.bluegaria.titlescreen.client.video.VlcVideoPlayer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Starts libVLC's native loading before the game's own main, i.e. before the window exists.
 *
 * <p>The loading screen background video is supposed to be on screen with its first frame the moment
 * the window appears, but loading libVLC and its plugin dependencies takes about a second. Doing that
 * from the client initializer happens after the window has been created, so the first rendered frames
 * still showed the vanilla loading background. This entrypoint buys that second back; the native
 * preparation is cached in {@link VlcVideoPlayer}, so the regular preload later is a no-op.</p>
 */
public class TitlescreenPreLaunch implements PreLaunchEntrypoint {

    private static final Logger LOGGER = LoggerFactory.getLogger("Titlescreen");

    @Override
    public void onPreLaunch() {
        String configuredPath = configuredLibVlcPath();
        LOGGER.info("Pre-launch: loading libVLC's native libraries early so the loading video's first "
                + "frame is ready when the window appears");
        VlcVideoPlayer.warmUpNativeLibraries(configuredPath);
    }

    /**
     * Reads the configured libVLC folder straight from the config file: Cloth Config's own holder is not
     * available yet this early, and the native directory cache is filled only once, so it has to be the
     * same path the game would use later.
     */
    private static String configuredLibVlcPath() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("titlescreen.json");
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonObject general = root.getAsJsonObject("general");
            if (general != null && general.has("libVlcPath")) {
                return general.get("libVlcPath").getAsString();
            }
        } catch (Throwable t) {
            LOGGER.debug("Could not read the libVLC folder from {} before launch", file, t);
        }
        return "";
    }
}
