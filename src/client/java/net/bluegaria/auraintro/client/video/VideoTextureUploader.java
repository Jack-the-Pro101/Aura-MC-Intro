package net.bluegaria.auraintro.client.video;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.DynamicTexture;

import java.nio.ByteBuffer;

/**
 * The render thread's direct path from a decoded frame buffer into the texture's GPU storage.
 *
 * <p>Minecraft's own {@code DynamicTexture.upload()} always routes through the texture's
 * {@code NativeImage}: the frame would first be copied there, only for the upload to read it
 * right after. Instead, the decoded buffer is handed to the device's command encoder - one
 * transfer to the GPU, no intermediate copy. From 26.2 on the encoder derives the pixel format
 * from the texture itself; older versions take it alongside the buffer.</p>
 */
final class VideoTextureUploader {
    private VideoTextureUploader() {
    }

    /**
     * Uploads {@code width x height} RGBA pixels from the start of {@code frame} into the
     * texture's GPU storage.
     *
     * @return {@code true} when the frame reached the GPU; the caller's copy-and-upload path is
     * only a safety net, in case a future Minecraft version breaks this one again
     */
    static boolean uploadRgba(DynamicTexture texture, ByteBuffer frame, int width, int height) {
        //? if >=26.2 {
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(
                texture.getTexture(), frame, 0, 0, 0, 0, width, height);
        //?} else {
        /*RenderSystem.getDevice().createCommandEncoder().writeToTexture(
                texture.getTexture(), frame, NativeImage.Format.RGBA, 0, 0, 0, 0, width, height);
        *///?}
        return true;
    }
}