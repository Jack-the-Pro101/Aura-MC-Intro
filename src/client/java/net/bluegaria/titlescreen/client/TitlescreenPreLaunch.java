package net.bluegaria.titlescreen.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.bluegaria.titlescreen.client.video.VlcNativeLibrary;
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
 * preparation is cached in {@link VlcNativeLibrary}, so the regular preload later is a no-op.</p>
 */
public class TitlescreenPreLaunch implements PreLaunchEntrypoint {

    private static final Logger LOGGER = LoggerFactory.getLogger("Titlescreen");

    @Override
    public void onPreLaunch() {
        Settings settings = readSettings();
        if (settings.debug()) {
            LOGGER.info("Pre-launch: loading libVLC's native libraries early so the loading video's first "
                    + "frame is ready when the window appears");
        }
        VlcNativeLibrary.warmUp(settings.libVlcPath());
    }

    /** The two config values this early entry point needs. */
    private record Settings(String libVlcPath, boolean debug) {
    }

    /**
     * Reads the configured libVLC folder straight from the config file: Cloth Config's own holder is not
     * available yet this early, and the native directory cache is filled only once, so it has to be the
     * same path the game would use later.
     */
    private static Settings readSettings() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("titlescreen.json");
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject general = JsonParser.parseReader(reader).getAsJsonObject()
                    .getAsJsonObject("general");
            if (general != null) {
                String path = general.has("libVlcPath") ? general.get("libVlcPath").getAsString() : "";
                boolean debug = general.has("debugLogging") && general.get("debugLogging").getAsBoolean();
                return new Settings(path, debug);
            }
        } catch (Throwable t) {
            LOGGER.debug("Could not read the titlescreen config from {} before launch", file, t);
        }
        return new Settings("", false);
    }
}
