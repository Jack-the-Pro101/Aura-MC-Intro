package net.bluegaria.titlescreen.client;

import com.sun.jna.Library;
import com.sun.jna.Native;
import net.bluegaria.titlescreen.client.config.TitlescreenConfigHolder;
import net.bluegaria.titlescreen.client.video.TitlescreenVideoManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

public class TitlescreenClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("Titlescreen");

    @Override
    public void onInitializeClient() {
        TitlescreenConfigHolder.register();
        prepareOpenAlDrivers();

        // Decode the loading screen background video's first frame right away, so it is on screen the
        // moment the loading screen appears instead of flashing the vanilla screen first. Nothing is
        // audible yet: the preload is silent and the configured volume is applied once the video is
        // actually shown.
        TitlescreenVideoManager.get().preloadLoadingBackgroundOnce();

        ClientTickEvents.END_CLIENT_TICK.register(client -> TitlescreenVideoManager.get().onClientTick(client));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> TitlescreenVideoManager.get().shutdown());
    }

    /**
     * Makes Minecraft's own OpenAL device open inside a sandboxed launcher (Flatpak/Snap): OpenAL probes
     * PipeWire first there and gives up, because its socket is not exposed, so it has to be told to use
     * the drivers that do work.
     *
     * <p>This is done inside the game process - OpenAL reads the variable when the sound engine starts,
     * which is after this initializer has run - so no launcher wrapper or environment setup is needed.
     * An existing {@code ALSOFT_DRIVERS} setting is never overridden.</p>
     */
    private static void prepareOpenAlDrivers() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) {
            return;
        }
        String existing = System.getenv("ALSOFT_DRIVERS");
        if (existing != null && !existing.isBlank()) {
            return;
        }
        try {
            LibC.INSTANCE.setenv("ALSOFT_DRIVERS", "pulse,alsa", 1);
            if (TitlescreenConfigHolder.get().general.debugLogging) {
                LOGGER.info("Set ALSOFT_DRIVERS=pulse,alsa so OpenAL can open an audio device");
            }
        } catch (Throwable t) {
            LOGGER.debug("Could not set ALSOFT_DRIVERS", t);
        }
    }

    /** Minimal libc binding for {@code setenv}. */
    private interface LibC extends Library {
        LibC INSTANCE = Native.load("c", LibC.class);

        int setenv(String name, String value, int overwrite);
    }
}

