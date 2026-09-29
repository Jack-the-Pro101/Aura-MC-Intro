package net.bluegaria.titlescreen.mixin.client;

//? if <26.1 {
/*import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
*///?}

/**
 * Exposes the private {@code innerBlit} of the immediate-mode {@code GuiGraphics} on 1.21.x, which
 * looks its texture up from the texture manager by location. That is exactly what is needed to
 * draw the video frame with a per-frame alpha. Only compiled into the 1.21.x builds.
 *
 * <p>Note the coordinate order: on these versions {@code innerBlit} takes {@code x0, x1, y0, y1}
 * (it swaps the middle two when forwarding to the blit render state, whose fields are
 * {@code x0, y0, x1, y1}). Getting this wrong produces an empty rectangle, which the render
 * state silently drops.</p>
 */
//? if <26.1 {
/*@Mixin(GuiGraphics.class)
public interface GuiGraphicsInvoker {

    @Invoker("innerBlit")
    void titlescreen$innerBlit(RenderPipeline pipeline, Identifier location,
                               int x0, int x1, int y0, int y1,
                               float u0, float u1, float v0, float v1, int color);
}
*///?}