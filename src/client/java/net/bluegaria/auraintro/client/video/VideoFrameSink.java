package net.bluegaria.auraintro.client.video;

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
 * producer always writes into the one buffer that is neither published nor being uploaded, so the
 * buffer the render thread is reading is never touched - no producer-side copy, no lock held during
 * the conversion, and frames produced while the render thread is busy are dropped, which is what an
 * intro video wants - a late frame is worse than a skipped one. The render thread claims the buffer
 * it is about to upload under the lock and uploads outside it: staging a 4K frame for the GPU can
 * take milliseconds, and holding the lock for that used to stall the decoder behind every upload.</p>
 *
 * <p>Everything the render thread does per frame is the upload itself. In particular the alpha
 * masking for an opaque scene happens on the decode thread (see {@link #setForceOpaque}), where there is
 * spare time: doing it per pixel on the render thread cost more than everything else in the frame
 * combined at 4K.</p>
 */
public final class VideoFrameSink {

    private static final Logger LOGGER = LoggerFactory.getLogger("Aura-Intro/Video");
    private static final int BYTES_PER_PIXEL = 4;
    /** One buffer published, one being written, one in reserve for an upload in flight: no copies, no tears. */
    private static final int BUFFER_COUNT = 3;

    private final Object lock = new Object();

    private final ByteBuffer[] buffers = new ByteBuffer[BUFFER_COUNT];
    private int latestIndex = -1;
    private boolean dirty;

    /**
     * The buffer the render thread is currently uploading, as an index and as the buffer itself.
     * The index keeps the producer away from that buffer while it is being read; the reference
     * still identifies it after a {@link #reallocate} reused the index for a new buffer. Volatile
     * because the upload runs outside the lock - see {@link #claimLatest} and {@link #finishUpload}.
     */
    private volatile int readingIndex = -1;
    private ByteBuffer readingBuffer;

    /**
     * A buffer whose memory could not be freed yet because the render thread was still reading it
     * when the frame size changed. Freed the moment that upload finishes (or on {@link #close()}).
     */
    private ByteBuffer retiredBuffer;

    /** Index of the buffer {@link #beginFrame} handed out; producer thread only. */
    private int writingIndex = -1;

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

    private long lastUploadNanos;

    // Written under the lock by the producer, read without it by the render thread's checks.
    private volatile long producedFrames;
    private volatile int width = -1;
    private volatile int height = -1;
    private volatile int visibleWidth = -1;
    private volatile int visibleHeight = -1;

    /**
     * Drops this player's alpha channel while producing frames, so its picture works as an opaque
     * background. Set for the loading scene (and the intro of the baked video, which is composited
     * the same way).
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
     * should be converted into - a buffer the render thread is neither publishing nor uploading.
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
            // The one buffer that is neither published (latest) nor claimed by an upload in flight
            // (reading). With three buffers that always leaves exactly one, which is what lets the
            // render thread upload outside the lock: the decoder never has to wait for an upload.
            int reading = this.readingIndex;
            for (int offset = 1; offset <= this.buffers.length; offset++) {
                int candidate = (this.latestIndex + offset) % this.buffers.length;
                if (candidate != this.latestIndex && candidate != reading) {
                    this.writingIndex = candidate;
                    return this.buffers[candidate];
                }
            }
            return null; // Unreachable with three buffers: latest and reading exclude at most two.
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
            if (this.width <= 0 || this.writingIndex < 0) {
                return;
            }
            this.visibleWidth = visibleWidth > 0 && visibleWidth <= this.width ? visibleWidth : this.width;
            this.visibleHeight = visibleHeight > 0 && visibleHeight <= this.height ? visibleHeight : this.height;
            this.latestIndex = this.writingIndex;
            this.writingIndex = -1;
            this.producedFrames++;
            this.dirty = true;
        }
    }

    /**
     * Consumer side, render thread: copies the newest published frame into the texture and uploads it.
     *
     * <p>The buffer is claimed under the lock but the upload itself runs outside it: the GPU
     * transfer can take milliseconds at 4K, and the decoder must not stall behind it. The claim
     * keeps the producer off the buffer for exactly as long as the upload reads it.</p>
     *
     * @return {@code true} when a new frame reached the texture
     */
    public boolean uploadIfDirty(DynamicTexture texture, int maxFps) {
        ByteBuffer frame;
        NativeImage image;
        int width;
        int height;
        synchronized (this.lock) {
            if (!this.dirty || this.latestIndex < 0 || this.width <= 0 || this.height <= 0) {
                return false;
            }
            image = texture.getPixels();
            if (image == null || image.getWidth() != this.width || image.getHeight() != this.height) {
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
            this.dirty = false;
            frame = claimLatest();
            if (frame == null) {
                return false;
            }
            width = this.width;
            height = this.height;
        }
        uploadClaimed(texture, image, frame, width, height);
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
        ByteBuffer frame;
        NativeImage image;
        int width;
        int height;
        synchronized (this.lock) {
            if (this.latestIndex < 0 || this.width <= 0 || this.height <= 0) {
                return false;
            }
            image = texture.getPixels();
            if (image == null || image.getWidth() != this.width || image.getHeight() != this.height) {
                return false;
            }
            frame = claimLatest();
            if (frame == null) {
                return false;
            }
            width = this.width;
            height = this.height;
        }
        uploadClaimed(texture, image, frame, width, height);
        return true;
    }

    /**
     * Uploads a buffer claimed by {@link #claimLatest} - outside the lock - and releases the claim.
     * The direct GPU path is tried first; the copy through the texture's own image is the fallback.
     */
    private void uploadClaimed(DynamicTexture texture, NativeImage image, ByteBuffer frame,
                               int width, int height) {
        try {
            frame.clear();
            if (!VideoTextureUploader.uploadRgba(texture, frame, width, height)) {
                MemoryUtil.memCopy(MemoryUtil.memAddress(frame), image.getPointer(),
                        (long) width * height * BYTES_PER_PIXEL);
                texture.upload();
            }
        } finally {
            finishUpload();
        }
    }

    /**
     * Marks the newest published buffer as being read by the render thread. Callers hold the lock;
     * the matching {@link #finishUpload()} runs after the upload, outside it.
     */
    private ByteBuffer claimLatest() {
        int index = this.latestIndex;
        ByteBuffer frame = index >= 0 ? this.buffers[index] : null;
        if (frame == null) {
            return null;
        }
        this.readingIndex = index;
        this.readingBuffer = frame;
        return frame;
    }

    /**
     * Releases the claim of {@link #claimLatest} and frees a buffer that was retired underneath it
     * by a size change. Also wakes a {@link #close()} that is waiting for the upload to finish.
     */
    private void finishUpload() {
        synchronized (this.lock) {
            this.readingIndex = -1;
            this.readingBuffer = null;
            if (this.retiredBuffer != null) {
                MemoryUtil.memFree(this.retiredBuffer);
                this.retiredBuffer = null;
            }
            this.lock.notifyAll();
        }
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
            // An upload in flight (it runs outside the lock) is still reading one of the buffers:
            // wait for it instead of freeing the memory underneath it. finishUpload() wakes this
            // wait; a wedged render thread leaks the buffers rather than crashing under it - the
            // same trade-off the player itself makes for its decoder threads.
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (this.readingIndex >= 0 && System.nanoTime() < deadline) {
                try {
                    this.lock.wait(100L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (this.readingIndex >= 0) {
                LOGGER.warn("An upload of this video was still running at close time - leaking its buffers");
                return;
            }
            for (int i = 0; i < this.buffers.length; i++) {
                MemoryUtil.memFree(this.buffers[i]);
                this.buffers[i] = null;
            }
            if (this.retiredBuffer != null) {
                MemoryUtil.memFree(this.retiredBuffer);
                this.retiredBuffer = null;
            }
            this.width = -1;
            this.height = -1;
            this.visibleWidth = -1;
            this.visibleHeight = -1;
            this.latestIndex = -1;
            this.writingIndex = -1;
            this.dirty = false;
            this.producedFrames = 0L;
        }
    }

    private void reallocate(int frameWidth, int frameHeight) {
        this.width = frameWidth;
        this.height = frameHeight;
        long size = (long) frameWidth * frameHeight * BYTES_PER_PIXEL;
        for (int i = 0; i < this.buffers.length; i++) {
            ByteBuffer old = this.buffers[i];
            this.buffers[i] = MemoryUtil.memAlloc((int) size);
            if (old == null) {
                continue;
            }
            if (old == this.readingBuffer) {
                // The render thread is still uploading from this buffer: freeing it here would
                // pull the memory out from under that upload. Park it; finishUpload() frees it
                // the moment the upload is done.
                this.retiredBuffer = old;
            } else {
                MemoryUtil.memFree(old);
            }
        }
        this.latestIndex = -1;
        this.dirty = false;
    }

    /**
     * The alpha byte of a pixel sits in the high byte of its little-endian RGBA word, so one long
     * covers the alpha of two neighbouring pixels - the mask for the vectorised alpha passes below.
     */
    private static final long ALPHA_MASK_2PX = 0xFF000000FF000000L;

    /**
     * Sets the alpha channel of every pixel to opaque, keeping the colour channels untouched.
     *
     * <p>Two pixels per long access - a quarter of the memory operations of the per-pixel loop this
     * replaced, which mattered because this pass runs on the decode thread for every frame of an
     * alpha-carrying clip.</p>
     */
    private static void forceOpaqueAlpha(ByteBuffer buffer, int pixelCount) {
        long address = MemoryUtil.memAddress(buffer);
        long end = address + (long) pixelCount * BYTES_PER_PIXEL;
        // The staging buffers come from malloc, so the long accesses stay aligned.
        for (; address + 8L <= end; address += 8L) {
            MemoryUtil.memPutLong(address, MemoryUtil.memGetLong(address) | ALPHA_MASK_2PX);
        }
        if (address < end) {
            // A lone trailing pixel of an odd frame size.
            MemoryUtil.memPutInt(address, MemoryUtil.memGetInt(address) | 0xFF000000);
        }
    }

    /**
     * Whether any pixel of the frame is not fully opaque - two pixels per long access, exiting on
     * the first pixel pair that is not fully opaque.
     */
    private static boolean hasAlpha(ByteBuffer buffer, int pixelCount) {
        long address = MemoryUtil.memAddress(buffer);
        long end = address + (long) pixelCount * BYTES_PER_PIXEL;
        for (; address + 8L <= end; address += 8L) {
            long word = MemoryUtil.memGetLong(address);
            if ((word & ALPHA_MASK_2PX) != ALPHA_MASK_2PX) {
                return true;
            }
        }
        if (address < end && (MemoryUtil.memGetInt(address) & 0xFF000000) != 0xFF000000) {
            return true;
        }
        return false;
    }
}
