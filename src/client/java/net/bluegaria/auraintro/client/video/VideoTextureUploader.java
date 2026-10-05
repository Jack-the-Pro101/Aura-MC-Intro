package net.bluegaria.auraintro.client.video;

//? if >=1.21.5 {
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
//?} else {
/*import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;
*///?}
import net.minecraft.client.renderer.texture.DynamicTexture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

/**
 * The render thread's direct path from a decoded frame buffer into the texture's GPU storage.
 *
 * <p>Minecraft's own {@code DynamicTexture.upload()} always routes through the texture's
 * {@code NativeImage}: the frame would first be copied there, only for the upload to read it
 * right after. Instead, the decoded buffer is handed to the device's command encoder - one
 * transfer to the GPU, no intermediate copy. From 26.2 on the encoder derives the pixel format
 * from the texture itself; older versions take it alongside the buffer. Before 1.21.5 there is no
 * device abstraction yet, and the same single transfer is a plain {@code glTexSubImage2D}.</p>
 */
final class VideoTextureUploader {

    private static final Logger LOGGER = LoggerFactory.getLogger("Aura-Intro/Video");

    private static boolean directUploadBroken;

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
        if (directUploadBroken) {
            return false;
        }
        try {
            //? if >=26.2 {
            RenderSystem.getDevice().createCommandEncoder().writeToTexture(
                    texture.getTexture(), frame, 0, 0, 0, 0, width, height);
            //?} else if >=1.21.9 {
            /*RenderSystem.getDevice().createCommandEncoder().writeToTexture(
                    texture.getTexture(), frame, NativeImage.Format.RGBA, 0, 0, 0, 0, width, height);
            *///?} else if >=1.21.6 {
            /*// Before 1.21.9 the pixels are taken as ints (the view shares the buffer's memory).
            RenderSystem.getDevice().createCommandEncoder().writeToTexture(
                    texture.getTexture(), frame.asIntBuffer(), NativeImage.Format.RGBA, 0, 0, 0, 0, width, height);
            *///?} else if >=1.21.5 {
            /*// 1.21.5 only takes the pixels as ints (the view shares the buffer's memory).
            RenderSystem.getDevice().createCommandEncoder().writeToTexture(
                    texture.getTexture(), frame.asIntBuffer(), NativeImage.Format.RGBA, 0, 0, 0, width, height);
            *///?} else {
            /*// The rows are tightly packed RGBA from the start of the buffer: reset the unpack state
            // a previous upload may have left behind (NativeImage.upload sets these per call too).
            GlStateManager._bindTexture(texture.getId());
            GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
            GlStateManager._texSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, width, height,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, MemoryUtil.memAddress(frame));
            *///?}
            return true;
        } catch (RuntimeException e) {
            // Render thread only, so a plain field is enough: stop trying after the first failure.
            directUploadBroken = true;
            LOGGER.warn("Direct texture upload failed - using the slower copying upload from now on", e);
            return false;
        }
    }
}