package net.bluegaria.auraintro.mixin.client;

import net.bluegaria.auraintro.client.video.AuraIntroVideoManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.LogoRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * The "MINECRAFT" wordmark on the title screen: hidden while the video plays and faded in once the video has
 * ended, when {@code general.fadeInAfterVideo} asks for that.
 */
@Mixin(LogoRenderer.class)
public abstract class LogoRendererMixin {

    /**
     * The wordmark is drawn with vanilla's screen fade as its alpha, and scaling that alpha is all it takes:
     * zero hides it, and a fraction fades it in.
     */
    @ModifyVariable(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IF)V",
            at = @At("HEAD"), argsOnly = true)
    private float auraintro$fadeInMinecraftLogo(float alpha) {
        return AuraIntroVideoManager.get().scaleLogoAlpha(alpha);
    }
}
