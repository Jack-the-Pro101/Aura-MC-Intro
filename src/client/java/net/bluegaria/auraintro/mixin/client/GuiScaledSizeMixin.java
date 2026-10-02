package net.bluegaria.auraintro.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.bluegaria.auraintro.client.video.AuraIntroVideoManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * While the title screen is drawn at its own GUI scale, the GUI size reported to everything it draws is
 * the size at that scale - exactly what vanilla reports when the game itself runs at it. Tooltips use it
 * to stay on screen; outside that pass both values are untouched.
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class GuiScaledSizeMixin {

    @ModifyReturnValue(method = "guiWidth", at = @At("RETURN"))
    private int auraintro$scaledGuiWidth(int guiWidth) {
        return AuraIntroVideoManager.get().scaledGuiWidth(guiWidth);
    }

    @ModifyReturnValue(method = "guiHeight", at = @At("RETURN"))
    private int auraintro$scaledGuiHeight(int guiHeight) {
        return AuraIntroVideoManager.get().scaledGuiHeight(guiHeight);
    }
}
