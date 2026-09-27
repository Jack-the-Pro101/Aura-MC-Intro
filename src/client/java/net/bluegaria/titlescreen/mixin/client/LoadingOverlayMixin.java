package net.bluegaria.titlescreen.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.bluegaria.titlescreen.client.video.TitlescreenVideoManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Takes over the vanilla loading overlay:
 *
 * <ul>
 *   <li>the overlay is kept (Mojang logo stays visible) until the configured video timestamp
 *       instead of immediately starting the vanilla fade-out,</li>
 *   <li>the progress bar fades with the configured timing rather than with vanilla's fixed
 *       one second fade.</li>
 * </ul>
 *
 * Everything is a no-op when the mod is disabled, the video file is missing or the config asks
 * for vanilla behaviour, in which case the game loads exactly like it always does.
 */
@Mixin(LoadingOverlay.class)
public abstract class LoadingOverlayMixin {

    @ModifyExpressionValue(
            method = "tick",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/LoadingOverlay;isReadyToFadeOut()Z"))
    private boolean titlescreen$holdOverlay(boolean original) {
        return original && !TitlescreenVideoManager.get().shouldHoldOverlay();
    }

    @WrapMethod(method = "extractProgressBar")
    private void titlescreen$progressBarAlpha(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1,
                                              float fade, Operation<Void> original) {
        float overridden = TitlescreenVideoManager.get().progressBarAlpha(fade);
        original.call(graphics, x0, y0, x1, y1, overridden);
    }

    /**
     * The loading background video is drawn right before the first MOJANG logo blit: that is after
     * vanilla painted its red background but before the logo and the progress bar, so the video
     * serves as a background for the "MOJANG STUDIOS" loading screen.
     */
    @Inject(
            method = "extractRenderState",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIIIIII)V",
                    ordinal = 0,
                    shift = At.Shift.BEFORE))
    private void titlescreen$extractLoadingBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                                      float partialTick, CallbackInfo ci) {
        TitlescreenVideoManager.get().extractLoadingBackground(graphics);
    }

    /**
     * Skips the two MOJANG logo blits while the loading background video is showing, so the loading
     * bar sits directly on the video instead of on top of a logo.
     */
    @WrapOperation(
            method = "extractRenderState",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIIIIII)V"))
    private void titlescreen$hideMojangLogo(GuiGraphicsExtractor instance, RenderPipeline pipeline, Identifier location,
                                            int x, int y, float u, float v, int width, int height,
                                            int regionWidth, int regionHeight, int textureWidth, int textureHeight,
                                            int color, Operation<Void> original) {
        if (TitlescreenVideoManager.get().shouldHideLoadingLogo()) {
            return;
        }
        original.call(instance, pipeline, location, x, y, u, v, width, height,
                regionWidth, regionHeight, textureWidth, textureHeight, color);
    }

    /**
     * The intro video is drawn on top of the overlay (so it covers the Mojang logo while it is
     * held), but still beneath the title screen buttons that are extracted as part of the screen.
     */
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void titlescreen$extractVideoLayer(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                               float partialTick, CallbackInfo ci) {
        TitlescreenVideoManager.get().extractVideoLayer(graphics);
    }
}
