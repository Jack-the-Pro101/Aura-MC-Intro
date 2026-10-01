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
 *
 * <p>Each widget is transformed with vanilla's own title screen anchors (see
 * {@link AuraIntroVideoManager#pushButtonScale}), so the result matches what the game itself
 * draws at that GUI scale - same sizes, same positions, text included.</p>
 */
@Mixin(Screen.class)
public abstract class ScreenMixin {

    @WrapOperation(
            method = "extractRenderState",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/Renderable;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V"))
    private void auraintro$scaledButtons(Renderable renderable, GuiGraphicsExtractor graphics, int mouseX,
                                           int mouseY, float partialTick, Operation<Void> original) {
        float factor = AuraIntroVideoManager.get().buttonScaleFactorFor((Screen) (Object) this);
        if (factor <= 0.0F || factor == 1.0F || !(renderable instanceof AbstractWidget widget)) {
            original.call(renderable, graphics, mouseX, mouseY, partialTick);
            return;
        }

        int guiWidth = graphics.guiWidth();
        int guiHeight = graphics.guiHeight();
        double[] scaledMouse = AuraIntroVideoManager.mouseToButtonSpace(
                widget, mouseX, mouseY, factor, guiWidth, guiHeight);
        Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix();
        AuraIntroVideoManager.pushButtonScale(pose, widget, factor, guiWidth, guiHeight);
        try {
            original.call(renderable, graphics, (int) Math.round(scaledMouse[0]),
                    (int) Math.round(scaledMouse[1]), partialTick);
        } finally {
            pose.popMatrix();
        }
    }
}
