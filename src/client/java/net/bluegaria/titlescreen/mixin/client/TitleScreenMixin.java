package net.bluegaria.titlescreen.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Title screen integration:
 * <ul>
 *   <li>replaces vanilla's fixed two second button fade with the configured video timestamps,</li>
 *   <li>fades the version/mod-count line, the splash text and the Realms badge on their own timetable,</li>
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

    /**
     * The version/mod-count line at the bottom left is drawn with a colour that has vanilla's own
     * screen fade as its alpha. Scaling that alpha is what makes it start transparent and fade in on
     * the configured timetable like the buttons - by the time the video hands the screen over,
     * vanilla's fade is already finished, so it was always fully visible.
     */
    @ModifyExpressionValue(
            method = "extractRenderState",
            //? if 1.21.10 {
            /*at = @At(value = "INVOKE", target = "Lnet/minecraft/util/ARGB;color(FI)I"))
            *///?} else {
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/ARGB;white(F)I"))
            //?}
    private int titlescreen$fadeVersionText(int original) {
        return TitlescreenVideoManager.get().scaleTitleTextAlpha(original);
    }

    /**
     * The splash text is drawn with vanilla's fade as its alpha, so it can be scaled the same way - and
     * skipped entirely when the mod is asked to hide it.
     */
    @WrapOperation(
            method = "extractRenderState",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/SplashRenderer;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;ILnet/minecraft/client/gui/Font;F)V"))
    private void titlescreen$fadeSplashText(SplashRenderer splash, GuiGraphicsExtractor graphics, int width,
                                            Font font, float alpha, Operation<Void> original) {
        if (TitlescreenVideoManager.get().shouldHideSplashText()) {
            return;
        }
        float scaled = TitlescreenVideoManager.get().scaleSplashAlpha(alpha);
        if (scaled <= 0.004F) {
            return;
        }
        original.call(splash, graphics, width, font, scaled);
    }
}
