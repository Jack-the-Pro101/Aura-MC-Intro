package net.bluegaria.auraintro.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.bluegaria.auraintro.client.video.AuraIntroVideoManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the title screen at the separate GUI scale from the config, without touching the rest of the UI
 * (or the vanilla GUI scale option).
 *
 * <p>The title screen is laid out at that scale to begin with (see
 * {@link AuraIntroVideoManager#layOutTitleScreen}), so drawing it is one uniform scale of everything the
 * screen draws - panorama, buttons, wordmark, splash, version line, copyright, Realms badge and tooltips
 * alike - and the result is pixel for pixel what the game itself draws at that GUI scale. Wrapping the
 * whole frame here is what brings the tooltips (drawn after the screen) along; a screen that draws the
 * title screen behind itself is covered by {@link TitleScreenMixin}.</p>
 *
 * <p>Also draws a persistent loop behind the other menus, right where they draw vanilla's panorama -
 * so the menu's blur goes over it the same way.</p>
 */
@Mixin(Screen.class)
public abstract class ScreenMixin {

    @WrapMethod(
            //? if >=26.1 {
            method = "extractRenderStateWithTooltipAndSubtitles")
            //?} else if >=1.21.9 {
            /*method = "renderWithTooltipAndSubtitles")
            *///?} else {
            /*method = "renderWithTooltip")
            *///?}
    private void auraintro$scaledTitleScreen(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                               float partialTick, Operation<Void> original) {
        Screen screen = (Screen) (Object) this;
        if (AuraIntroVideoManager.titleScreenScaleFactor(screen) <= 0.0F) {
            original.call(graphics, mouseX, mouseY, partialTick);
            return;
        }
        AuraIntroVideoManager.get().drawScaledTitleScreen(graphics, screen, mouseX, mouseY,
                (x, y) -> original.call(graphics, x, y, partialTick));
    }

    //? if >=26.1 {
    @Inject(method = "extractPanorama", at = @At("TAIL"))
    //?} else {
    /*@Inject(method = "renderPanorama", at = @At("TAIL"))
    *///?}
    private void auraintro$loopBehindMenus(GuiGraphicsExtractor graphics, float partialTick, CallbackInfo ci) {
        // The title screen draws its own video (TitleScreenMixin); this is for the other menus.
        if (!((Object) this instanceof TitleScreen) && AuraIntroVideoManager.get().drawMenuBackgroundVideo(graphics)) {
            //? if >=1.21.6 {
            // Vanilla blurs everything before the stratum it blurs in, and that is the one the panorama
            // was drawn in: a stratum of its own puts the video under the blur too.
            graphics.nextStratum();
            //?} else {
            /*// The blur is a post-process of whatever reached the framebuffer: the video has to be drawn
            // by then, not still be waiting in the GUI's batch.
            graphics.flush();
            *///?}
        }
    }
}
