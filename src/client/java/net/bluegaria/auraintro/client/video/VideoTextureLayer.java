package net.bluegaria.auraintro.client.video;

import com.mojang.blaze3d.systems.RenderSystem;
//? if >=26.1 {
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
//?}
import net.bluegaria.auraintro.client.config.AuraIntroConfig;
//? if >=26.1 {
import net.bluegaria.auraintro.mixin.client.GuiGraphicsExtractorInvoker;
//?} else {
/*import net.bluegaria.auraintro.mixin.client.GuiGraphicsInvoker;
*///?}
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the GPU texture for one scene's video and knows how to draw it through Minecraft's GUI render
 * state.
 *
 * <p>The decoded picture arrives in its own buffer, so the whole texture is drawn
 * while the aspect ratio for the destination rectangle comes from the real track size
 * ({@link VideoPlayer#sourceWidth()}). Frames themselves are copied in by {@link VideoFrameSink}.</p>
 */
public final class VideoTextureLayer {

    private static final Logger LOGGER = LoggerFactory.getLogger("Aura-Intro/Video");

    private final Identifier identifier;
    private final String label;

    private DynamicTexture texture;
    /** A texture for a new frame size, kept out of sight until it has received a frame. */
    private DynamicTexture pendingTexture;

    public VideoTextureLayer(String namespace, String path, String label) {
        this.identifier = Identifier.fromNamespaceAndPath(namespace, path);
        this.label = label;
    }

    /**
     * Uploads the newest decoded frame, creating or resizing the texture as needed.
     *
     * @return {@code true} when a new frame actually reached the texture
     */
    public boolean upload(VideoPlayer player, int maxFps) {
        return copyInto(player, maxFps, false);
    }

    /**
     * Puts the newest decoded frame into the texture even when no new frame has arrived.
     *
     * <p>Used before the scene that shows the video becomes visible - see
     * {@link VideoFrameSink#uploadLatest}.</p>
     */
    public boolean warmUp(VideoPlayer player) {
        return copyInto(player, 0, true);
    }

    private boolean copyInto(VideoPlayer player, int maxFps, boolean latest) {
        VideoFrameSink sink = player.sink();
        int bufferWidth = sink.width();
        int bufferHeight = sink.height();
        if (bufferWidth <= 0 || bufferHeight <= 0) {
            return false;
        }
        DynamicTexture target = targetFor(bufferWidth, bufferHeight);
        boolean uploaded = latest ? sink.uploadLatest(target) : sink.uploadIfDirty(target, maxFps);
        if (!uploaded) {
            return false;
        }
        if (target != this.texture) {
            promote(target, bufferWidth, bufferHeight);
        }
        return true;
    }

    /**
     * The texture a frame of this size should go into.
     *
     * <p>When the size changes (the decoder reallocates its buffer, which happens when a preloaded video starts
     * playing) a new texture is prepared off to the side instead of replacing the one on screen: a fresh
     * texture is empty, and drawing it - it is blended - let the title screen's panorama and buttons flash
     * through until the next frame arrived.</p>
     */
    private DynamicTexture targetFor(int width, int height) {
        if (matches(this.texture, width, height)) {
            return this.texture;
        }
        if (!matches(this.pendingTexture, width, height)) {
            if (this.pendingTexture != null) {
                this.pendingTexture.close();
            }
            this.pendingTexture = new DynamicTexture(() -> this.label, width, height, true);
        }
        return this.pendingTexture;
    }

    private static boolean matches(DynamicTexture texture, int width, int height) {
        return texture != null
                && texture.getPixels().getWidth() == width
                && texture.getPixels().getHeight() == height;
    }

    /** Makes a now-filled texture the one that is drawn, and drops the texture it replaces. */
    private void promote(DynamicTexture target, int width, int height) {
        DynamicTexture previous = this.texture;
        Minecraft.getInstance().getTextureManager().register(this.identifier, target);
        this.pendingTexture = null;
        this.texture = target;
        if (previous != null) {
            previous.close();
        }
        LOGGER.debug("Video texture for {} is now {}x{}", this.label, width, height);
    }

    /** Draws the whole texture across the screen using the requested fit mode and opacity. */
    public boolean draw(GuiGraphicsExtractor graphics, VideoPlayer player,
                        AuraIntroConfig.VideoFit fit, float alpha) {
        DynamicTexture target = this.texture;
        if (target == null || alpha <= 0.004F) {
            return false;
        }
        VideoFrameSink sink = player.sink();
        int visibleWidth = sink.visibleWidth();
        int visibleHeight = sink.visibleHeight();
        if (visibleWidth <= 0 || visibleHeight <= 0) {
            return false;
        }
        int aspectWidth = player.sourceWidth();
        int aspectHeight = player.sourceHeight();
        if (aspectWidth <= 0 || aspectHeight <= 0) {
            aspectWidth = visibleWidth;
            aspectHeight = visibleHeight;
        }
        int guiWidth = graphics.guiWidth();
        int guiHeight = graphics.guiHeight();
        if (guiWidth <= 0 || guiHeight <= 0) {
            return false;
        }
        // How large the picture is on screen, in physical pixels: the producer scales a source
        // bigger than that down during its RGBA conversion (see VideoFrameSink.noteOutputSize).
        // Written on every drawn frame, so window resizes track along on their own.
        int guiScale = Minecraft.getInstance().getWindow().getGuiScale();
        sink.noteOutputSize(guiWidth * guiScale, guiHeight * guiScale);

        float[] rect = fitRect(fit, aspectWidth, aspectHeight, guiWidth, guiHeight);
        // The 1.21.x branch below: GuiGraphics.innerBlit takes its int coordinates as
        // (x0, x1, y0, y1) - see GuiGraphicsInvoker for details.
        //? if >=26.1 {
        ((GuiGraphicsExtractorInvoker) graphics).auraintro$innerBlit(
                RenderPipelines.GUI_TEXTURED,
                target.getTextureView(),
                videoSampler(),
                Math.round(rect[0]), Math.round(rect[1]), Math.round(rect[2]), Math.round(rect[3]),
                rect[4], rect[5], rect[6], rect[7],
                ARGB.white(alpha));
        //?} else {
        /*((GuiGraphicsInvoker) graphics).auraintro$innerBlit(
                RenderPipelines.GUI_TEXTURED,
                this.identifier,
                Math.round(rect[0]), Math.round(rect[2]), Math.round(rect[1]), Math.round(rect[3]),
                rect[4], rect[5], rect[6], rect[7],
                ARGB.white(alpha));
        *///?}
        return true;
    }

    //? if >=26.1 {
    /**
     * The sampler the video is drawn with.
     *
     * <p>Minecraft creates a texture like this with a {@code NEAREST}/{@code REPEAT} sampler - nearest for
     * both magnification and minification - so the GPU point-samples the video at whatever scale it ends up
     * being drawn at. A 4K clip on a 1440p screen then shows every texel as a block, and a 1080p clip on a
     * 4K screen shows each pixel as a 2x2 square, while a video player scales it with a real filter. Linear
     * filtering is exact for the 1:1 case, so it only ever helps; {@code CLAMP_TO_EDGE} also removes the
     * wrapped edge pixel that {@code REPEAT} showed along the right and bottom edges.</p>
     */
    private static GpuSampler videoSampler() {
        return RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
    }
    //?}

    public void release(TextureManager textureManager) {
        DynamicTexture current = this.texture;
        this.texture = null;
        if (current != null) {
            textureManager.release(this.identifier);
            current.close();
        }
        if (this.pendingTexture != null) {
            this.pendingTexture.close();
            this.pendingTexture = null;
        }
    }

    /**
     * Computes the destination rectangle plus the texture coordinates.
     *
     * @return {x0, y0, x1, y1, u0, u1, v0, v1}
     */
    private static float[] fitRect(AuraIntroConfig.VideoFit fit, int videoWidth, int videoHeight,
                                   int guiWidth, int guiHeight) {
        double videoAspect = videoWidth / (double) videoHeight;
        double screenAspect = guiWidth / (double) guiHeight;
        float x0 = 0.0F;
        float y0 = 0.0F;
        float x1 = guiWidth;
        float y1 = guiHeight;
        float u0 = 0.0F;
        float u1 = 1.0F;
        float v0 = 0.0F;
        float v1 = 1.0F;

        switch (fit) {
            case STRETCH -> {
                // Full screen, aspect ratio intentionally ignored.
            }
            case CONTAIN -> {
                if (videoAspect > screenAspect) {
                    float height = (float) (guiWidth / videoAspect);
                    y0 = (guiHeight - height) / 2.0F;
                    y1 = y0 + height;
                } else {
                    float width = (float) (guiHeight * videoAspect);
                    x0 = (guiWidth - width) / 2.0F;
                    x1 = x0 + width;
                }
            }
            case COVER -> {
                if (videoAspect > screenAspect) {
                    float visible = (float) (screenAspect / videoAspect);
                    u0 = (1.0F - visible) / 2.0F;
                    u1 = 1.0F - u0;
                } else {
                    float visible = (float) (videoAspect / screenAspect);
                    v0 = (1.0F - visible) / 2.0F;
                    v1 = 1.0F - v0;
                }
            }
        }
        return new float[]{x0, y0, x1, y1, u0, u1, v0, v1};
    }
}
