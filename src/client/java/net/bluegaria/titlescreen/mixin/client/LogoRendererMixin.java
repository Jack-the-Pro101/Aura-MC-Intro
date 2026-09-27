package net.bluegaria.titlescreen.mixin.client;

import net.bluegaria.titlescreen.client.video.TitlescreenVideoManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.LogoRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides the "MINECRAFT" wordmark on the title screen when configured to do so.
 */
@Mixin(LogoRenderer.class)
public abstract class LogoRendererMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IF)V",
            at = @At("HEAD"), cancellable = true)
    private void titlescreen$hideVanillaLogo(GuiGraphicsExtractor graphics, int width, float alpha,
                                             CallbackInfo ci) {
        if (TitlescreenVideoManager.get().shouldHideMinecraftLogo()) {
            ci.cancel();
        }
    }
}
