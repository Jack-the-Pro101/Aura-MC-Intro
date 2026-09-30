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
 * <p>The decoder converts each frame directly into one of three staging buffers and publishes it; the
 * render thread uploads the newest published buffer straight into the texture's GPU storage
 * ({@link VideoTextureUploader} - no detour through the texture's own {@link NativeImage} copy). The
 * producer always writes into the buffer after the
 * published one, so the buffer the render thread is reading is never touched - no producer-side copy,
 * no lock held during conversion, and frames produced while the render thread is busy are dropped,
 * which is what an intro video wants - a late frame is worse than a skipped one.</p>
 *
 * <p>Everything the render thread does per frame is the upload itself. In particular the alpha
 * masking for an opaque scene happens on the decode thread (see {@link #setForceOpaque}), where there is
 * spare time: doing it per pixel on the render thread cost more than everything else in the frame
 * combined at 4K.</p>
 */
public final class VideoFrameSink {

    private static final Logger LOGGER = LoggerFactory.getLogger("Titlescreen/Video");
    private static final int BYTES_PER_PIXEL = 4;
    /** One buffer being displayed/copied, one being written, one in reserve: no copies, no tears. */
    private static final int BUFFER_COUNT = 3;

    private final Object lock = new Object();

    private final ByteBuffer[] buffers = new ByteBuffer[BUFFER_COUNT];
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

    /**
     * On-screen size (physical pixels) the picture is being drawn at, as last seen by the render
     * thread. Zero while unknown; the producer then converts at the source resolution. Read by the
     * decode thread to scale oversized sources down during the RGBA conversion.
     */
    private volatile int outputWidth;
    private volatile int outputHeight;

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
     * Tells the producer the physical pixel size the picture is being drawn at, so a source larger
     * than the window can be scaled during the RGBA conversion instead of being converted and
     * uploaded at full size only for the GPU to throw most of the pixels away again. Written by the
     * render thread on every drawn frame (which is also what keeps window resizes tracked);
     * non-positive sizes are ignored.
     */
    public void noteOutputSize(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        this.outputWidth = width;
        this.outputHeight = height;
    }

    /** Physical width the picture is being drawn at, or {@code 0} while unknown. */
    public int outputWidth() {
        return this.outputWidth;
    }

    /** Physical height the picture is being drawn at, or {@code 0} while unknown. */
    public int outputHeight() {
        return this.outputHeight;
    }

    /**
     * Producer side. Called from the decoder's frame thread: returns the buffer the next frame
     * should be converted into - the one buffer the render thread is not reading and not holding.
     * The conversion (swscale) writes into it directly; {@link #commitFrame} then publishes it.
     *
     * @param frameWidth    width of the decoded picture (the buffers are resized to match)
     * @param frameHeight   height of the decoded picture
     * @return a direct RGBA buffer of {@code frameWidth x frameHeight} pixels
     */
    public ByteBuffer beginFrame(int frameWidth, int frameHeight) {
        synchronized (this.lock) {
            if (frameWidth <= 0 || frameHeight <= 0) {
                return null;
            }
            if (this.width != frameWidth || this.height != frameHeight) {
                this.reallocate(frameWidth, frameHeight);
            }
            return this.buffers[(this.latestIndex + 1) % this.buffers.length];
        }
    }

    /**
     * Runs the opaque-scene alpha handling on a producer-owned frame buffer - outside the lock,
     * where the spare time is.
     */
    public void processAlpha(ByteBuffer buffer, int pixelCount) {
        if (!this.forceOpaque) {
            return;
        }
        if (this.alphaProbeFrames > 0 && !this.alphaPresent) {
            this.alphaPresent = hasAlpha(buffer, pixelCount);
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
            forceOpaqueAlpha(buffer, pixelCount);
        }
    }

    /**
     * Producer side: publishes the buffer that {@link #beginFrame} handed out (one commit per
     * begun frame). The lock is held only for the bookkeeping, never for the conversion.
     *
     * @param visibleWidth  width of the visible picture
     * @param visibleHeight height of the visible picture
     */
    public void commitFrame(int visibleWidth, int visibleHeight) {
        synchronized (this.lock) {
            if (this.width <= 0) {
                return;
            }
            this.visibleWidth = visibleWidth > 0 && visibleWidth <= this.width ? visibleWidth : this.width;
            this.visibleHeight = visibleHeight > 0 && visibleHeight <= this.height ? visibleHeight : this.height;
            this.latestIndex = (this.latestIndex + 1) % this.buffers.length;
            this.producedFrames++;
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
            // Uploaded under the lock so a concurrent producer cannot reuse this buffer half-way
            // through; from the moment the call returns, the GPU owns the bytes it read.
            this.dirty = false;
            ByteBuffer frame = this.buffers[this.latestIndex];
            frame.clear();
            if (VideoTextureUploader.uploadRgba(texture, frame, this.width, this.height)) {
                return true;
            }
            MemoryUtil.memCopy(MemoryUtil.memAddress(frame), image.getPointer(),
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
            if (image == null || image.getWidth() != this.width || image.getHeight() != this.height) {
                return false;
            }
            ByteBuffer frame = this.buffers[this.latestIndex];
            frame.clear();
            if (VideoTextureUploader.uploadRgba(texture, frame, this.width, this.height)) {
                return true;
            }
            MemoryUtil.memCopy(MemoryUtil.memAddress(frame), image.getPointer(),
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
        this.latestIndex = -1;
        this.dirty = false;
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
