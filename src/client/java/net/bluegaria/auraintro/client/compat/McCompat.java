package net.bluegaria.auraintro.client.compat;

import me.shedaniel.autoconfig.ConfigData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.Screen;
//? if 1.21.10 {
/*import me.shedaniel.autoconfig.AutoConfig;
*///?} else {
import me.shedaniel.autoconfig.AutoConfigClient;
//?}

/**
 * Small facade over the few vanilla API points that moved between the supported
 * Minecraft versions. Everything here is decided at build time by Stonecutter, so
 * each build contains exactly one branch - there is no runtime version sniffing.
 */
public final class McCompat {

    private McCompat() {
    }

    /** The screen the GUI currently shows. */
    public static Screen currentScreen(Minecraft minecraft) {
        //? if >=26.2 {
        return minecraft.gui.screen();
        //?} else {
        /*return minecraft.screen;
        *///?}
    }

    /** The overlay currently covering the screen (the loading overlay while it is up). */
    public static Overlay currentOverlay(Minecraft minecraft) {
        //? if >=26.2 {
        return minecraft.gui.overlay();
        //?} else {
        /*return minecraft.getOverlay();
        *///?}
    }

    /** Removes the current overlay without finishing it. */
    public static void clearOverlay(Minecraft minecraft) {
        //? if >=26.2 {
        minecraft.gui.setOverlay(null);
        //?} else {
        /*minecraft.setOverlay(null);
        *///?}
    }

    /** Mirrors vanilla's re-init of a screen after the loading overlay is unloaded. */
    public static void reinitScreen(Screen screen, Minecraft minecraft, int width, int height) {
        //? if 1.21.10 {
        /*screen.init(minecraft, width, height);
        *///?} else {
        screen.init(width, height);
        //?}
    }

    /** Opens the Cloth Config screen for the given config class. */
    public static <T extends ConfigData> Screen configScreen(Class<T> configClass, Screen parent) {
        //? if 1.21.10 {
        /*return AutoConfig.getConfigScreen(configClass, parent).get();
        *///?} else {
        return AutoConfigClient.getConfigScreen(configClass, parent).get();
        //?}
    }
}