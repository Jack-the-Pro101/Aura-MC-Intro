package net.bluegaria.auraintro.client.compat;

//? if fabric {
import net.fabricmc.loader.api.FabricLoader;
//?} else {
/*import net.neoforged.fml.loading.FMLPaths;
*///?}

import java.nio.file.Path;

/**
 * The few things the mod needs from the mod loader. Like {@link McCompat}, decided at build time by
 * Stonecutter: each jar contains exactly one loader's branch.
 */
public final class Platform {

    private Platform() {
    }

    /** The game's config directory ({@code <game dir>/config}). */
    public static Path configDir() {
        //? if fabric {
        return FabricLoader.getInstance().getConfigDir();
        //?} else {
        /*return FMLPaths.CONFIGDIR.get();
        *///?}
    }

    /** The game directory, which relative paths in the config are resolved against. */
    public static Path gameDir() {
        //? if fabric {
        return FabricLoader.getInstance().getGameDir();
        //?} else {
        /*return FMLPaths.GAMEDIR.get();
        *///?}
    }
}
