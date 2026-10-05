package net.bluegaria.auraintro.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.bluegaria.auraintro.client.video.AuraIntroVideoManager;
import net.bluegaria.auraintro.client.video.ScaledTitleScreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.SplashRenderer;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Title screen integration:
 * <ul>
 *   <li>replaces vanilla's fixed two second button fade with the configured video timestamps,</li>
 *   <li>fades the version/mod-count line, the splash text and the Realms badge on their own timetable,</li>
 *   <li>lays the whole screen out at the configured GUI scale before {@code init()} (see
 *       {@link net.bluegaria.auraintro.mixin.client.ScreenMixin} for the drawing side),</li>
 *   <li>applies the configured vertical offset to the buttons after {@code init()},</li>
 *   <li>optionally hides the splash text.</li>
 * </ul>
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin implements ScaledTitleScreen {

    /** The factor the current layout was made for, or -1 when it uses the game's own GUI scale. */
    @Unique
    private float auraintro$guiScaleFactor = -1.0F;

    @Accessor("fading")
    public abstract void setFading(boolean fading);

    @Override
    public float auraintro$guiScaleFactor() {
        return this.auraintro$guiScaleFactor;
    }

    /**
     * Gives the screen the size it has at the configured GUI scale before vanilla lays it out, so every
     * element - the Realms badge and the copyright line included - ends up where the game itself would
     * put it at that scale. Every path into a layout ({@code init(width, height)}, {@code resize},
     * {@code rebuildWidgets}) runs through here.
     */
    @Inject(method = "init", at = @At("HEAD"))
    private void auraintro$applyGuiScale(CallbackInfo ci) {
        this.auraintro$guiScaleFactor = AuraIntroVideoManager.layOutTitleScreen(
                (Screen) (Object) this, this.auraintro$guiScaleFactor > 0.0F);
    }

    /**
     * Applies the title screen's GUI scale when something draws the screen directly instead of through
     * the normal frame - the Friends overlay draws it blurred behind itself this way. Without this the
     * screen's scaled layout came out at the game's GUI scale there. On the normal frame
     * {@link ScreenMixin} has already applied the scale, and this draws as it is.
     */
    @WrapMethod(method = "extractRenderState")
    private void auraintro$scaledWhenDrawnDirectly(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                                     float partialTick, Operation<Void> original) {
        Screen screen = (Screen) (Object) this;
        if (AuraIntroVideoManager.titleScreenScaleFactor(screen) <= 0.0F) {
            original.call(graphics, mouseX, mouseY, partialTick);
            return;
        }
        AuraIntroVideoManager.get().drawScaledTitleScreen(graphics, screen, mouseX, mouseY,
                (x, y) -> original.call(graphics, x, y, partialTick));
    }

    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void auraintro$applyButtonFade(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                             float partialTick, CallbackInfo ci) {
        float alpha = AuraIntroVideoManager.get().buttonAlphaOverride();
        if (alpha < 0.0F && !AuraIntroVideoManager.get().consumeButtonAlphaRestore()) {
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
    private void auraintro$applyButtonOffset(CallbackInfo ci) {
        AuraIntroVideoManager.get().applyButtonYOffset((Screen) (Object) this);
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
    private void auraintro$drawVideoBehindWidgets(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                                    float partialTick, CallbackInfo ci) {
        AuraIntroVideoManager.get().drawTitleScreenVideo(graphics);
    }

    //? if >=1.21.6 {
    /**
     * The version/mod-count line at the bottom left is drawn with a colour that has vanilla's own
     * screen fade as its alpha. Scaling that alpha is what makes it start transparent and fade in on
     * the configured timetable like the buttons - by the time the video hands the screen over,
     * vanilla's fade is already finished, so it was always fully visible. (NeoForge draws its branding
     * lines in the same colour.)
     */
    @ModifyExpressionValue(
            method = "extractRenderState",
            //? if <1.21.11 || neoforge {
            /*at = @At(value = "INVOKE", target = "Lnet/minecraft/util/ARGB;color(FI)I"))
            *///?} else {
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/ARGB;white(F)I"))
            //?}
    private int auraintro$fadeVersionText(int original) {
        return AuraIntroVideoManager.get().scaleTitleTextAlpha(original);
    }
    //?}

    /*
     * The version/mod-count line before 1.21.6: its colour is 0xFFFFFF | alpha, built inline, so the draw call
     * itself is wrapped. NeoForge draws its branding lines (the version line among them) in lambdas of
     * render, hence a NeoForge target of its own. A text colour with an alpha below 4 is drawn fully opaque by
     * these versions' font renderer, so a faded-out line is skipped altogether.
     */
    //? if <1.21.6 && fabric {
    /*@WrapOperation(
            method = "extractRenderState",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)I"))
    private int auraintro$fadeVersionText(GuiGraphicsExtractor graphics, Font font, String text, int x, int y,
                                            int color, Operation<Integer> original) {
        int scaled = AuraIntroVideoManager.get().scaleTitleTextAlpha(color);
        if ((scaled >>> 24) < 4) {
            return x;
        }
        return original.call(graphics, font, text, x, y, scaled);
    }
    *///?}

    //? if <1.21.6 && neoforge {
    /*@WrapOperation(
            method = "/lambda\\$render\\$\\d+/",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)I"))
    private int auraintro$fadeVersionText(GuiGraphicsExtractor graphics, Font font, String text, int x, int y,
                                            int color, Operation<Integer> original) {
        int scaled = AuraIntroVideoManager.get().scaleTitleTextAlpha(color);
        if ((scaled >>> 24) < 4) {
            return x;
        }
        return original.call(graphics, font, text, x, y, scaled);
    }
    *///?}

    /**
     * The splash text is drawn with vanilla's fade as its alpha, so it can be scaled the same way - and
     * skipped entirely when the mod is asked to hide it.
     */
    //? if >=1.21.6 {
    @WrapOperation(
            method = "extractRenderState",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/SplashRenderer;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;ILnet/minecraft/client/gui/Font;F)V"))
    private void auraintro$fadeSplashText(SplashRenderer splash, GuiGraphicsExtractor graphics, int width,
                                            Font font, float alpha, Operation<Void> original) {
        if (AuraIntroVideoManager.get().shouldHideSplashText()) {
            return;
        }
        float scaled = AuraIntroVideoManager.get().scaleSplashAlpha(alpha);
        if (scaled <= 0.004F) {
            return;
        }
        original.call(splash, graphics, width, font, scaled);
    }
    //?} else {
    /*// Before 1.21.6 the splash takes its alpha as the top byte of a colour.
    @WrapOperation(
            method = "extractRenderState",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/SplashRenderer;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;ILnet/minecraft/client/gui/Font;I)V"))
    private void auraintro$fadeSplashText(SplashRenderer splash, GuiGraphicsExtractor graphics, int width,
                                            Font font, int alphaBits, Operation<Void> original) {
        if (AuraIntroVideoManager.get().shouldHideSplashText()) {
            return;
        }
        float scaled = AuraIntroVideoManager.get().scaleSplashAlpha((alphaBits >>> 24) / 255.0F);
        int scaledBits = Math.round(scaled * 255.0F);
        // The font renderer draws an alpha below 4 fully opaque.
        if (scaledBits < 4) {
            return;
        }
        original.call(splash, graphics, width, font, scaledBits << 24);
    }
    *///?}
}
