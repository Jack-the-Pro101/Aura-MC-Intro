package net.bluegaria.auraintro.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.bluegaria.auraintro.client.compat.McCompat;
import net.bluegaria.auraintro.client.video.AuraIntroVideoManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Keeps mouse hit testing in sync with the scaled title screen: a click is transformed into the
 * screen's own GUI pixels, the coordinate space its widgets are laid out and drawn in.
 */
@Mixin(AbstractWidget.class)
public abstract class AbstractWidgetMixin {

    @WrapMethod(method = "isMouseOver(DD)Z")
    private boolean auraintro$scaledHitTest(double mouseX, double mouseY, Operation<Boolean> original) {
        float factor = AuraIntroVideoManager.titleScreenScaleFactor(
                McCompat.currentScreen(Minecraft.getInstance()));
        if (factor <= 0.0F) {
            return original.call(mouseX, mouseY);
        }
        return original.call(mouseX / factor, mouseY / factor);
    }
}
