package net.bluegaria.auraintro.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.bluegaria.auraintro.client.compat.McCompat;
import net.bluegaria.auraintro.client.video.AuraIntroVideoManager;
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
    private boolean auraintro$scaledHitTest(double mouseX, double mouseY, Operation<Boolean> original) {
        Minecraft minecraft = Minecraft.getInstance();
        float factor = AuraIntroVideoManager.get()
                .buttonScaleFactorFor(McCompat.currentScreen(minecraft));
        if (factor <= 0.0F || factor == 1.0F) {
            return original.call(mouseX, mouseY);
        }
        double[] transformed = AuraIntroVideoManager.mouseToButtonSpace((AbstractWidget) (Object) this,
                mouseX, mouseY, factor,
                minecraft.getWindow().getGuiScaledWidth(), minecraft.getWindow().getGuiScaledHeight());
        return original.call(transformed[0], transformed[1]);
    }
}
