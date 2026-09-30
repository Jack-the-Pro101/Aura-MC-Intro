package net.bluegaria.auraintro.mixin.client;

//? if >=26.1 {
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
//?}

/**
 * Exposes the private {@code innerBlit} overload that accepts an arbitrary GPU texture plus a
 * tint colour. That is exactly what is needed to draw the video frame with a per-frame alpha.
 * Only compiled into the 26.x builds (1.21.x draws through GuiGraphicsInvoker instead). On
 * other versions this file is generated empty - the class is only referenced by code that is
 * version-gated as well, and the matching mixin is registered by the versioned mixin config.
 */
//? if >=26.1 {
@Mixin(GuiGraphicsExtractor.class)
public interface GuiGraphicsExtractorInvoker {

    @Invoker("innerBlit")
    void auraintro$innerBlit(RenderPipeline pipeline, GpuTextureView textureView, GpuSampler sampler,
                               int x0, int y0, int x1, int y1,
                               float u0, float u1, float v0, float v1, int color);
}
//?}
