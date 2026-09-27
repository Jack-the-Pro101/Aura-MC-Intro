package net.bluegaria.titlescreen.mixin.client;

import net.bluegaria.titlescreen.client.video.TitlescreenVideoManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.SplashRenderer;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Title screen integration:
 * <ul>
 *   <li>replaces vanilla's fixed two second button fade with the configured video timestamps,</li>
 *   <li>applies the configured vertical offset to the buttons after {@code init()},</li>
 *   <li>optionally hides the splash text.</li>
 * </ul>
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin {

    @Accessor("fading")
    public abstract void setFading(boolean fading);

    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void titlescreen$applyButtonFade(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                             float partialTick, CallbackInfo ci) {
        float alpha = TitlescreenVideoManager.get().buttonAlphaOverride();
        if (alpha < 0.0F && !TitlescreenVideoManager.get().consumeButtonAlphaRestore()) {
            return;
        }
        if (alpha < 0.0F) {
            alpha = 1.0F;
        }
        // Stop vanilla from fading the widgets on its own timetable.
        this.setFading(false);
        for (GuiEventListener child : ((Screen) (Object) this).children()) {
            if (child instanceof AbstractWidget widget) {
                widget.setAlpha(alpha);
            }
        }
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void titlescreen$applyButtonOffset(CallbackInfo ci) {
        TitlescreenVideoManager.get().applyButtonYOffset((Screen) (Object) this);
    }

    /**
     * Draws the video on top of the panorama but <em>before</em> the widgets are extracted, so the
     * title screen buttons always end up above the video.
     *
     * <p>The title screen is rendered <em>underneath</em> the loading overlay, so during the overlay's
     * fade-out, and in the gap between the overlay disappearing and the hand-over, this is the only thing
     * covering the panorama and buttons. The manager picks the layer that fits the current scene.</p>
     */
    @Inject(
            method = "extractRenderState",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/Screen;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
                    shift = At.Shift.BEFORE))
    private void titlescreen$drawVideoBehindWidgets(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                                    float partialTick, CallbackInfo ci) {
        TitlescreenVideoManager.get().drawTitleScreenVideo(graphics);
    }

    @Redirect(
            method = "extractRenderState",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/SplashRenderer;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;ILnet/minecraft/client/gui/Font;F)V"))
    private void titlescreen$hideSplashText(SplashRenderer splash, GuiGraphicsExtractor graphics, int width,
                                            Font font, float alpha) {
        if (!TitlescreenVideoManager.get().shouldHideSplashText()) {
            splash.extractRenderState(graphics, width, font, alpha);
        }
    }
}
