package net.bluegaria.titlescreen.client.config;

import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.ConfigHolder;
import me.shedaniel.autoconfig.serializer.GsonConfigSerializer;

/**
 * Owns the Cloth Config registration. Kept separate from {@link TitlescreenConfig} so the
 * runtime can always fall back to sane defaults, even before client initialisation ran.
 */
public final class TitlescreenConfigHolder {

    private static final TitlescreenConfig FALLBACK = new TitlescreenConfig();
    private static ConfigHolder<TitlescreenConfig> holder;

    private TitlescreenConfigHolder() {
    }

    public static void register() {
        holder = AutoConfig.register(TitlescreenConfig.class, GsonConfigSerializer::new);
        try {
            FALLBACK.validatePostLoad();
        } catch (ConfigData.ValidationException ignored) {
            // The defaults are always valid.
        }
    }

    public static TitlescreenConfig get() {
        ConfigHolder<TitlescreenConfig> current = holder;
        return current == null ? FALLBACK : current.getConfig();
    }

    public static void save() {
        ConfigHolder<TitlescreenConfig> current = holder;
        if (current != null) {
            current.save();
        }
    }
}
