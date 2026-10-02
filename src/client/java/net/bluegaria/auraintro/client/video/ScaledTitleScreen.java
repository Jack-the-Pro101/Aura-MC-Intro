package net.bluegaria.auraintro.client.video;

/**
 * Implemented by the title screen (through {@link net.bluegaria.auraintro.mixin.client.TitleScreenMixin}):
 * the factor its last layout was made for, so drawing and hit testing always use the same scale as the
 * layout they belong to - even if the config or the window changed since and the screen has not been
 * re-initialised yet.
 */
public interface ScaledTitleScreen {

    /**
     * The title screen's GUI scale relative to the game's, or {@code -1} when it is laid out at the game's
     * own GUI scale.
     */
    float auraintro$guiScaleFactor();
}
