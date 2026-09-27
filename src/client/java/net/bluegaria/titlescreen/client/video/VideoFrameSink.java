package net.bluegaria.titlescreen.client.video;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.lwjgl.system.MemoryUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

/**
 * Bridges the (native, off-thread) libVLC frame callbacks with the render thread.
 *
 * <p>The producer writes a whole RGBA frame (top-down, 4 bytes per pixel) into an off-heap
 * staging buffer and publishes it. The render thread copies the newest published frame into
 * its own staging buffer and uploads that to a {@link DynamicTexture}. Frames that are
 * produced while the render thread is busy are simply dropped, which is exactly what we want
 * for an intro video.</p>
 */
public final class VideoFrameSink {

    private static final int BYTES_PER_PIXEL = 4;


    private final Object lock = new Object();

    private ByteBuffer[] producerBuffers = new ByteBuffer[2];
    private int writeIndex;
    private int latestIndex = -1;
    private boolean dirty;

    private ByteBuffer uploadBuffer;

    private long producedFrames;

    private int width = -1;
    private int height = -1;
    private int visibleWidth = -1;
    private int visibleHeight = -1;

    private long lastUploadNanos;

    /**
     * Producer side. Called from a libVLC native thread.
     *
     * @param source      direct buffer holding the first video plane
     * @param sourcePitch row stride (in bytes) of the source plane
     * @param visibleWidth  width of the actually visible picture (libVLC pads the buffer to an
     *                      aligned size, so this can be smaller than {@code frameWidth})
     * @param visibleHeight height of the actually visible picture
     * @param bgrFallback when libVLC could not give us RGBA, the frame is BGRX and has to be
     *                    swizzled (and given an opaque alpha channel) manually
     */
    public void offerFrame(ByteBuffer source, int sourcePitch, int frameWidth, int frameHeight,
                           int visibleWidth, int visibleHeight, boolean bgrFallback, int maxDimension) {
        if (source == null || frameWidth <= 0 || frameHeight <= 0) {
            return;
        }

        int rowBytes = frameWidth * BYTES_PER_PIXEL;
        int pitch = sourcePitch > 0 ? sourcePitch : rowBytes;

        // Very large sources (4K and up) are sampled down while copying, which keeps the per frame
        // memory traffic and the texture upload bounded without any extra pass.
        int step = 1;
        if (maxDimension > 0) {
            int largest = Math.max(frameWidth, frameHeight);
            if (largest > maxDimension) {
                step = (largest + maxDimension - 1) / maxDimension;
            }
        }
        int outWidth = (frameWidth + step - 1) / step;
        int outHeight = (frameHeight + step - 1) / step;
        int outRowBytes = outWidth * BYTES_PER_PIXEL;

        synchronized (this.lock) {
            if (this.width != outWidth || this.height != outHeight) {
                this.reallocate(outWidth, outHeight);
            }
            this.visibleWidth = visibleWidth > 0 && visibleWidth <= frameWidth ? visibleWidth : frameWidth;
            this.visibleHeight = visibleHeight > 0 && visibleHeight <= frameHeight ? visibleHeight : frameHeight;

            ByteBuffer target = this.producerBuffers[this.writeIndex];
            long sourceBase = MemoryUtil.memAddress(source);
            long targetBase = MemoryUtil.memAddress(target);
            for (int y = 0; y < outHeight; y++) {
                long sourceRow = sourceBase + (long) (y * step) * pitch;
                long targetRow = targetBase + (long) y * outRowBytes;
                if (step == 1) {
                    MemoryUtil.memCopy(sourceRow, targetRow, rowBytes);
                } else {
                    for (int x = 0; x < outWidth; x++) {
                        MemoryUtil.memCopy(sourceRow + (long) x * step * BYTES_PER_PIXEL,
                                targetRow + (long) x * BYTES_PER_PIXEL, BYTES_PER_PIXEL);
                    }
                }
            }

            if (bgrFallback) {
                swizzleBgrToRgba(target, outWidth * outHeight);
            }

            // Publish the freshly written buffer and keep the other one for the next write.
            this.producedFrames++;
            this.latestIndex = this.writeIndex;
            this.writeIndex = 1 - this.writeIndex;
            this.dirty = true;
        }
    }

    /** BGRX -> RGBA, also forcing the alpha channel to opaque. */
    private static void swizzleBgrToRgba(ByteBuffer buffer, int pixelCount) {
        java.nio.IntBuffer ints = buffer.asIntBuffer();
        for (int i = 0; i < pixelCount; i++) {
            int pixel = ints.get(i);
            int red = (pixel >>> 16) & 0xFF;
            int blue = pixel & 0xFF;
            ints.put(i, 0xFF000000 | (blue << 16) | (pixel & 0x0000FF00) | red);
        }
    }

    /**
     * Consumer side. Called on the render thread.
     *
     * @param forceOpaque when {@code true} the alpha channel is overwritten with opaque, so the frame
     *                    covers whatever is behind it - used by the loading scene, which is a
     *                    background and must not let the vanilla loading screen show through the
     *                    video's transparent pixels
     * @return {@code true} when a new frame was uploaded to the texture
     */
    public boolean uploadIfDirty(DynamicTexture texture, int maxFps, boolean forceOpaque) {
        ByteBuffer published;
        synchronized (this.lock) {
            if (!this.dirty || this.latestIndex < 0 || this.width <= 0 || this.height <= 0) {
                return false;
            }
            if (maxFps > 0) {
                long minIntervalNanos = 1_000_000_000L / maxFps;
                long now = System.nanoTime();
                if (this.lastUploadNanos != 0L && now - this.lastUploadNanos < minIntervalNanos) {
                    return false;
                }
                this.lastUploadNanos = now;
            }
            this.dirty = false;
            published = this.producerBuffers[this.latestIndex];

            // Copy while holding the lock so a concurrent producer cannot reuse this buffer
            // half-way through our read.
            if (this.uploadBuffer == null) {
                return false;
            }
            MemoryUtil.memCopy(MemoryUtil.memAddress(published), MemoryUtil.memAddress(this.uploadBuffer),
                    (long) this.width * this.height * BYTES_PER_PIXEL);
        }

        if (forceOpaque) {
            forceOpaqueAlpha(this.uploadBuffer, this.width * this.height);
        }

        NativeImage image = texture.getPixels();
        if (image.getWidth() != this.width || image.getHeight() != this.height) {
            return false;
        }
        MemoryUtil.memCopy(MemoryUtil.memAddress(this.uploadBuffer), image.getPointer(),
                (long) this.width * this.height * BYTES_PER_PIXEL);
        texture.upload();
        return true;
    }

    /**
     * Forces the alpha channel of every pixel to opaque. Deliberately written through the same int view
     * as {@link #swizzleBgrToRgba}, so it never has to assume a byte order of its own.
     */
    private static void forceOpaqueAlpha(ByteBuffer buffer, int pixelCount) {
        java.nio.IntBuffer ints = buffer.asIntBuffer();
        for (int i = 0; i < pixelCount; i++) {
            ints.put(i, ints.get(i) | 0xFF000000);
        }
    }

    /**
     * Copies the newest published frame into the texture regardless of the dirty flag.
     *
     * <p>Used to put the video on screen before the scene that shows it appears. The frame is already decoded
     * and being held (the loading scene pauses on it), so waiting for the next dirty frame would leave the
     * first frames of the next scene with nothing drawn - the panorama and buttons flash through instead.</p>
     */
    public boolean uploadLatest(DynamicTexture texture, boolean forceOpaque) {
        ByteBuffer published;
        synchronized (this.lock) {
            if (this.latestIndex < 0 || this.width <= 0 || this.height <= 0 || this.uploadBuffer == null) {
                return false;
            }
            published = this.producerBuffers[this.latestIndex];
            MemoryUtil.memCopy(MemoryUtil.memAddress(published), MemoryUtil.memAddress(this.uploadBuffer),
                    (long) this.width * this.height * BYTES_PER_PIXEL);
        }
        if (forceOpaque) {
            forceOpaqueAlpha(this.uploadBuffer, this.width * this.height);
        }
        NativeImage image = texture.getPixels();
        if (image.getWidth() != this.width || image.getHeight() != this.height) {
            return false;
        }
        MemoryUtil.memCopy(MemoryUtil.memAddress(this.uploadBuffer), image.getPointer(),
                (long) this.width * this.height * BYTES_PER_PIXEL);
        texture.upload();
        return true;
    }

    /**
     * How many frames libVLC has delivered so far. This is deliberately independent of our render
     * thread: it only tells whether the video output is still producing frames at all, which is what
     * "is this playback stalled" has to be based on (using the uploaded-frame count meant that a busy
     * loading screen looked like a stalled video).
     */
    public long producedFrames() {
        return this.producedFrames;
    }

    public int width() {
        return this.width;
    }

    public int height() {
        return this.height;
    }

    /** Width of the visible picture inside the (possibly padded) buffer. */
    public int visibleWidth() {
        return this.visibleWidth > 0 ? this.visibleWidth : this.width;
    }

    /** Height of the visible picture inside the (possibly padded) buffer. */
    public int visibleHeight() {
        return this.visibleHeight > 0 ? this.visibleHeight : this.height;
    }

    public void close() {
        synchronized (this.lock) {
            for (int i = 0; i < this.producerBuffers.length; i++) {
                MemoryUtil.memFree(this.producerBuffers[i]);
                this.producerBuffers[i] = null;
            }
            MemoryUtil.memFree(this.uploadBuffer);
            this.uploadBuffer = null;
            this.width = -1;
            this.height = -1;
            this.visibleWidth = -1;
            this.visibleHeight = -1;
            this.latestIndex = -1;
            this.writeIndex = 0;
            this.dirty = false;
            this.producedFrames = 0L;
        }
    }

    private void reallocate(int frameWidth, int frameHeight) {
        this.width = frameWidth;
        this.height = frameHeight;
        long size = (long) frameWidth * frameHeight * BYTES_PER_PIXEL;
        for (int i = 0; i < this.producerBuffers.length; i++) {
            MemoryUtil.memFree(this.producerBuffers[i]);
            this.producerBuffers[i] = MemoryUtil.memAlloc((int) size);
        }
        MemoryUtil.memFree(this.uploadBuffer);
        this.uploadBuffer = MemoryUtil.memAlloc((int) size);
        this.writeIndex = 0;
        this.latestIndex = -1;
        this.dirty = false;
    }
}
