package net.bluegaria.titlescreen.mixin.client;

import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.server.packs.resources.ReloadInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Read-only access to the private state of the vanilla loading overlay, used to detect the
 * exact moment the game finished loading (without having to copy any vanilla logic).
 */
@Mixin(LoadingOverlay.class)
public interface LoadingOverlayAccessor {

    @Accessor("reload")
    ReloadInstance getReload();

    @Accessor("fadeIn")
    boolean isFadeIn();

    @Accessor("fadeInStart")
    long getFadeInStart();

    @Accessor("onFinish")
    Consumer<Optional<Throwable>> getOnFinish();
}
