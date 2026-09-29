package net.bluegaria.titlescreen.client.video;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

/**
 * Bridges the decoder's frame-producing thread with the render thread.
 *
 * <p>The decoder writes a whole RGBA frame (top-down) into one of two staging buffers and publishes it; the
 * render thread copies the newest published frame straight into the texture's {@link NativeImage} and
 * uploads it. Frames produced while the render thread is busy are dropped, which is what an intro video
 * wants - a late frame is worse than a skipped one.</p>
 *
 * <p>Everything the render thread does per frame is one copy plus the upload. In particular the alpha
 * masking for an opaque scene happens on the decode thread (see {@link #setForceOpaque}), where there is
 * spare time: doing it per pixel on the render thread cost more than everything else in the frame
 * combined at 4K.</p>
 */
public final class VideoFrameSink {

    private static final Logger LOGGER = LoggerFactory.getLogger("Titlescreen/Video");
    private static final int BYTES_PER_PIXEL = 4;

    private final Object lock = new Object();

    /** Two alternating buffers: the producer writes one while the other holds the published frame. */
    private final ByteBuffer[] buffers = new ByteBuffer[2];
    private int writeIndex;
    private int latestIndex = -1;
    private boolean dirty;

    /** Read by the producer thread, set once by the scene that owns this player. */
    private volatile boolean forceOpaque;

    /**
     * How many frames are scanned for real transparency before the alpha pass is dropped. An opaque video
     * does not need it, and at 4K that pass costs more than everything else the producer does per frame.
     */
    private static final int ALPHA_PROBE_FRAMES = 5;
    private int alphaProbeFrames = ALPHA_PROBE_FRAMES;
    private boolean alphaPresent;

    private long producedFrames;
    private long lastUploadNanos;

    private int width = -1;
    private int height = -1;
    private int visibleWidth = -1;
    private int visibleHeight = -1;

    /**
     * Drops this player's alpha channel while producing frames, so its picture works as an opaque
     * background. Set for the loading scene (and for a baked single video, whose intro is composited the
     * same way); the separate intro video keeps its transparency.
     */
    public void setForceOpaque(boolean forceOpaque) {
        this.forceOpaque = forceOpaque;
    }

    /**
     * Producer side. Called from the decoder's frame thread.
     *
     * @param source        direct buffer holding the first video plane
     * @param sourcePitch   row stride (in bytes) of the source plane
     * @param frameWidth    width of the decoded picture
     * @param frameHeight   height of the decoded picture
     * @param visibleWidth  width of the visible picture
     * @param visibleHeight height of the visible picture
     * @param bgrFallback   the source could not give us RGBA, so the frame is BGRX and has to be swizzled
     *                      (and given an opaque alpha channel) manually
     */
    public void offerFrame(ByteBuffer source, int sourcePitch, int frameWidth, int frameHeight,
                           int visibleWidth, int visibleHeight, boolean bgrFallback) {
        if (source == null || frameWidth <= 0 || frameHeight <= 0) {
            return;
        }

        int rowBytes = frameWidth * BYTES_PER_PIXEL;
        int pitch = sourcePitch > 0 ? sourcePitch : rowBytes;

        synchronized (this.lock) {
            if (this.width != frameWidth || this.height != frameHeight) {
                this.reallocate(frameWidth, frameHeight);
            }
            this.visibleWidth = visibleWidth > 0 && visibleWidth <= frameWidth ? visibleWidth : frameWidth;
            this.visibleHeight = visibleHeight > 0 && visibleHeight <= frameHeight ? visibleHeight : frameHeight;

            ByteBuffer target = this.buffers[this.writeIndex];
            long sourceBase = MemoryUtil.memAddress(source);
            long targetBase = MemoryUtil.memAddress(target);
            for (int y = 0; y < frameHeight; y++) {
                MemoryUtil.memCopy(sourceBase + (long) y * pitch, targetBase + (long) y * rowBytes, rowBytes);
            }

            if (bgrFallback) {
                swizzleBgrToRgba(target, frameWidth * frameHeight);
            } else if (this.forceOpaque) {
                if (this.alphaProbeFrames > 0 && !this.alphaPresent) {
                    this.alphaPresent = hasAlpha(target, frameWidth * frameHeight);
                    if (!this.alphaPresent && --this.alphaProbeFrames == 0) {
                        // Five frames without a single transparent pixel: this video has no transparency to
                        // drop, so stop rewriting the alpha channel of every frame - at 4K that pass alone
                        // starves the decoder's output thread, which in turn stalls the demuxer and the audio
                        // with it.
                        this.forceOpaque = false;
                        LOGGER.debug("Video has no transparency - skipping the alpha pass from now on");
                    }
                }
                if (this.forceOpaque) {
                    forceOpaqueAlpha(target, frameWidth * frameHeight);
                }
            }

            // Publish the freshly written buffer and keep the other one for the next write.
            this.producedFrames++;
            this.latestIndex = this.writeIndex;
            this.writeIndex = 1 - this.writeIndex;
            this.dirty = true;
        }
    }

    /**
     * Consumer side, render thread: copies the newest published frame into the texture and uploads it.
     *
     * @return {@code true} when a new frame reached the texture
     */
    public boolean uploadIfDirty(DynamicTexture texture, int maxFps) {
        synchronized (this.lock) {
            if (!this.dirty || this.latestIndex < 0 || this.width <= 0 || this.height <= 0) {
                return false;
            }
            NativeImage image = texture.getPixels();
            if (image.getWidth() != this.width || image.getHeight() != this.height) {
                // The texture still holds a frame of another size; the layer resizes it on the next upload.
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
            // Copied under the lock so a concurrent producer cannot reuse this buffer half-way through.
            this.dirty = false;
            MemoryUtil.memCopy(MemoryUtil.memAddress(this.buffers[this.latestIndex]), image.getPointer(),
                    (long) this.width * this.height * BYTES_PER_PIXEL);
        }
        texture.upload();
        return true;
    }

    /**
     * Consumer side, render thread: copies the published frame into the texture even when no new frame
     * has arrived.
     *
     * <p>Used to put the video on screen before the scene that shows it appears. The frame is already
     * decoded and being held (the loading scene pauses on it), so waiting for the next dirty frame would
     * leave the first frames of the next scene with nothing drawn - the panorama and buttons flash
     * through instead.</p>
     *
     * @return {@code true} when the texture now holds the frame
     */
    public boolean uploadLatest(DynamicTexture texture) {
        synchronized (this.lock) {
            if (this.latestIndex < 0 || this.width <= 0 || this.height <= 0) {
                return false;
            }
            NativeImage image = texture.getPixels();
            if (image.getWidth() != this.width || image.getHeight() != this.height) {
                return false;
            }
            MemoryUtil.memCopy(MemoryUtil.memAddress(this.buffers[this.latestIndex]), image.getPointer(),
                    (long) this.width * this.height * BYTES_PER_PIXEL);
        }
        texture.upload();
        return true;
    }

    /**
     * How many frames the decoder has delivered. Deliberately independent of the render thread: it only says
     * whether the video output is producing frames at all, which is what "is this playback stalled" has
     * to be based on (the uploaded-frame count made a busy loading screen look like a stalled video).
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
            for (int i = 0; i < this.buffers.length; i++) {
                MemoryUtil.memFree(this.buffers[i]);
                this.buffers[i] = null;
            }
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
        for (int i = 0; i < this.buffers.length; i++) {
            MemoryUtil.memFree(this.buffers[i]);
            this.buffers[i] = MemoryUtil.memAlloc((int) size);
        }
        this.writeIndex = 0;
        this.latestIndex = -1;
        this.dirty = false;
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

    /** Sets the alpha channel of every pixel to opaque, keeping the colour channels untouched. */
    private static void forceOpaqueAlpha(ByteBuffer buffer, int pixelCount) {
        long base = MemoryUtil.memAddress(buffer);
        for (int i = 0; i < pixelCount; i++) {
            long address = base + (long) i * 4L;
            MemoryUtil.memPutInt(address, MemoryUtil.memGetInt(address) | 0xFF000000);
        }
    }

    /** Whether any pixel of the frame is not fully opaque. */
    private static boolean hasAlpha(ByteBuffer buffer, int pixelCount) {
        long base = MemoryUtil.memAddress(buffer);
        long end = base + (long) pixelCount * BYTES_PER_PIXEL;
        for (long address = base + 3L; address < end; address += 4L) {
            if (MemoryUtil.memGetByte(address) != (byte) 0xFF) {
                return true;
            }
        }
        return false;
    }
}
