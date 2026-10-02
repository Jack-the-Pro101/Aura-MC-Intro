package net.bluegaria.auraintro.mixin.client;

import net.bluegaria.auraintro.client.video.AuraIntroVideoManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.MusicManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps vanilla's menu music quiet while the intro video is providing the title screen's sound -
 * without this, the game starts its own soundtrack a few seconds in and talks over the clip.
 *
 * <p>{@link MusicManager#tick() tick()} is cancelled for as long as the video is supplying audio;
 * the internal song countdown never advances meanwhile, so once the video ends (fades out, freezes
 * after its end, or is stopped), the menu music comes back on vanilla's own timetable. A song that
 * is already playing when the video starts (for example on a resource reload replay) is stopped.</p>
 *
 * <p>With a loop region the video never ends, so the music takes over the first time it loops
 * instead - and right away rather than after vanilla's countdown (which can be several seconds).</p>
 */
@Mixin(MusicManager.class)
public abstract class MusicManagerMixin {

    @Shadow @Final private Minecraft minecraft;

    @Shadow private SoundInstance currentMusic;

    @Shadow private int nextSongDelay;

    /** One-shot diagnostic: when suppression first kicked in (debug logging only). */
    @Unique
    private boolean auraintro$suppressionLogged;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void auraintro$suppressWhileVideoPlays(CallbackInfo ci) {
        if (AuraIntroVideoManager.get().consumeMenuMusicStart()) {
            // The video just handed the sound over (its loop muted it): vanilla's tick below starts
            // the menu music now.
            this.nextSongDelay = 0;
        }
        if (!AuraIntroVideoManager.get().suppressMenuMusic()) {
            return;
        }
        if (!this.auraintro$suppressionLogged) {
            this.auraintro$suppressionLogged = true;
            AuraIntroVideoManager.logMenuMusicSuppressed();
        }
        if (this.currentMusic != null) {
            this.minecraft.getSoundManager().stop(this.currentMusic);
            this.currentMusic = null;
        }
        ci.cancel();
    }
}