package net.bluegaria.auraintro.mixin.client;

//? if <1.21.2 {
/*import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
*///?} else if <1.21.6 {
/*import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.function.Function;
*///?} else if <26.1 {
/*import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
*///?}

/**
 * Exposes the non-public {@code innerBlit} of the {@code GuiGraphics} of 1.21.x, which looks its texture
 * up from the texture manager by location. That is exactly what is needed to draw the video frame with a
 * per-frame alpha. Only compiled into the 1.21.x builds (26.x draws through GuiGraphicsExtractorInvoker).
 *
 * <p>Note the coordinate order: on these versions {@code innerBlit} takes {@code x0, x1, y0, y1}.
 * Getting this wrong produces an empty rectangle, which is silently dropped.</p>
 *
 * <p>The signature follows the GUI renderer underneath: 1.21.1 draws immediately with float colour
 * channels and a z offset, 1.21.2-1.21.5 batch by render type, 1.21.6+ extract render state for a
 * render pipeline.</p>
 */
//? if <1.21.2 {
/*@Mixin(GuiGraphics.class)
public interface GuiGraphicsInvoker {

    @Invoker("innerBlit")
    void auraintro$innerBlit(Identifier location, int x0, int x1, int y0, int y1, int blitOffset,
                               float u0, float u1, float v0, float v1,
                               float red, float green, float blue, float alpha);
}
*///?} else if <1.21.6 {
/*@Mixin(GuiGraphics.class)
public interface GuiGraphicsInvoker {

    @Invoker("innerBlit")
    void auraintro$innerBlit(Function<Identifier, RenderType> renderType, Identifier location,
                               int x0, int x1, int y0, int y1,
                               float u0, float u1, float v0, float v1, int color);
}
*///?} else if <26.1 {
/*@Mixin(GuiGraphics.class)
public interface GuiGraphicsInvoker {

    @Invoker("innerBlit")
    void auraintro$innerBlit(RenderPipeline pipeline, Identifier location,
                               int x0, int x1, int y0, int y1,
                               float u0, float u1, float v0, float v1, int color);
}
*///?}
