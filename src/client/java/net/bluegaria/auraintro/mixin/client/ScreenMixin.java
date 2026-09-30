package net.bluegaria.auraintro.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.bluegaria.auraintro.client.video.AuraIntroVideoManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import org.joml.Matrix3x2fStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Applies the separate "buttons GUI scale" to the title screen widgets only, without touching
 * the rest of the UI (or the vanilla GUI scale option).
 */
@Mixin(Screen.class)
public abstract class ScreenMixin {

    @WrapOperation(
            method = "extractRenderState",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/Renderable;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V"))
    private void auraintro$scaledButtons(Renderable renderable, GuiGraphicsExtractor graphics, int mouseX,
                                           int mouseY, float partialTick, Operation<Void> original) {
        float scale = AuraIntroVideoManager.get().buttonGuiScaleFor((Screen) (Object) this);
        if (scale <= 0.0F || scale == 1.0F || !(renderable instanceof AbstractWidget)) {
            original.call(renderable, graphics, mouseX, mouseY, partialTick);
            return;
        }

        double[] scaledMouse = AuraIntroVideoManager.toButtonSpace(mouseX, mouseY, scale);
        float centerX = graphics.guiWidth() / 2.0F;
        float centerY = graphics.guiHeight() / 2.0F;
        Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(centerX, centerY);
        pose.scale(scale, scale);
        pose.translate(-centerX, -centerY);
        try {
            original.call(renderable, graphics, (int) Math.round(scaledMouse[0]),
                    (int) Math.round(scaledMouse[1]), partialTick);
        } finally {
            pose.popMatrix();
        }
    }
}
