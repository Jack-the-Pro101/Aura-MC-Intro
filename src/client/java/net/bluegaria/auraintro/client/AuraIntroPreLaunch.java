package net.bluegaria.auraintro.client;

//? if fabric {
import net.bluegaria.auraintro.client.video.FfmpegNativeLibrary;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads the bundled FFmpeg libraries before the game's own main, i.e. before the window exists.
 *
 * <p>The loading screen background video is supposed to be on screen with its first frame the moment
 * the window appears, but extracting and loading the libraries takes a moment. Doing that from the
 * client initializer happens after the window has been created, so the first rendered frames still
 * showed the vanilla loading background. This entrypoint buys that time back; the preparation is
 * cached in {@link FfmpegNativeLibrary}, so the regular preload later is a no-op.</p>
 */
public class AuraIntroPreLaunch implements PreLaunchEntrypoint {

    private static final Logger LOGGER = LoggerFactory.getLogger("Aura-Intro");

    @Override
    public void onPreLaunch() {
        if (debugLogging()) {
            LOGGER.info("Pre-launch: loading the bundled FFmpeg libraries early so the loading video's "
                    + "first frame is ready when the window appears");
        }
        FfmpegNativeLibrary.warmUp();
    }

    /**
     * Reads the debug-logging switch straight from the config file: Cloth Config's own holder is not
     * available yet this early, and the log line above only matters when it is on.
     */
    private static boolean debugLogging() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("aura-intro.json");
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            var general = com.google.gson.JsonParser.parseReader(reader)
                    .getAsJsonObject().getAsJsonObject("general");
            return general != null && general.has("debugLogging")
                    && general.get("debugLogging").getAsBoolean();
        } catch (Throwable t) {
            LOGGER.debug("Could not read the aura-intro config from {} before launch", file, t);
            return false;
        }
    }
}
//?}
