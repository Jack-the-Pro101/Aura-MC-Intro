package net.bluegaria.titlescreen.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.bluegaria.titlescreen.client.compat.McCompat;
import net.bluegaria.titlescreen.client.video.TitlescreenVideoManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Keeps mouse hit testing in sync with the scaled button rendering: a click is transformed into
 * the same coordinate space the widget is drawn in.
 */
@Mixin(AbstractWidget.class)
public abstract class AbstractWidgetMixin {

    @WrapMethod(method = "isMouseOver(DD)Z")
    private boolean titlescreen$scaledHitTest(double mouseX, double mouseY, Operation<Boolean> original) {
        float scale = TitlescreenVideoManager.get().buttonGuiScaleFor(McCompat.currentScreen(Minecraft.getInstance()));
        if (scale <= 0.0F || scale == 1.0F) {
            return original.call(mouseX, mouseY);
        }
        double[] transformed = TitlescreenVideoManager.toButtonSpace(mouseX, mouseY, scale);
        return original.call(transformed[0], transformed[1]);
    }
}
