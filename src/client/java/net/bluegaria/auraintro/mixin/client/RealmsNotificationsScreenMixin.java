package net.bluegaria.auraintro.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.realmsclient.gui.screens.RealmsNotificationsScreen;
import net.bluegaria.auraintro.client.video.AuraIntroVideoManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Fades the Realms notification badge sprites.
 *
 * <p>{@code extractIcons} blits them with the overload that takes no colour at all, so the badge has no
 * alpha of its own - and the title screen only draws this overlay once its own fade finished, which is why
 * the badge pops in. Re-issuing each blit with an alpha makes it fade in with the rest of the badges.</p>
 *
 * <p>The sprites are placed against vanilla's button layout, so they are also moved by the configured
 * button offset to stay on the Realms button. (The GUI scale needs nothing here: this overlay is laid
 * out with the title screen's size, which already is the size at that scale.)</p>
 */
@Mixin(RealmsNotificationsScreen.class)
public abstract class RealmsNotificationsScreenMixin {

    @WrapOperation(
            method = "extractIcons",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"))
    private void auraintro$fadeBadgeSprites(GuiGraphicsExtractor graphics, RenderPipeline pipeline,
                                              Identifier sprite, int x, int y, int width, int height,
                                              Operation<Void> original) {
        float alpha;
        int offsetY;
        try {
            alpha = AuraIntroVideoManager.get().titleBadgeAlpha(true);
            offsetY = AuraIntroVideoManager.get().buttonsYOffset();
        } catch (Throwable t) {
            // The badge is decoration: if the mod's state cannot be read, draw it the vanilla way.
            original.call(graphics, pipeline, sprite, x, y, width, height);
            return;
        }
        if (alpha <= 0.004F) {
            return;
        }
        graphics.blitSprite(pipeline, sprite, x, y + offsetY, width, height, alpha);
    }
}