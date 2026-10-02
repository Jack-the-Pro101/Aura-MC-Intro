package net.bluegaria.auraintro.client.config;

import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.ConfigHolder;
import me.shedaniel.autoconfig.serializer.GsonConfigSerializer;

/**
 * Owns the Cloth Config registration. Kept separate from {@link AuraIntroConfig} so the
 * runtime can always fall back to sane defaults, even before client initialisation ran.
 */
public final class AuraIntroConfigHolder {

    private static final AuraIntroConfig FALLBACK = new AuraIntroConfig();
    private static ConfigHolder<AuraIntroConfig> holder;

    private AuraIntroConfigHolder() {
    }

    public static void register() {
        holder = AutoConfig.register(AuraIntroConfig.class, GsonConfigSerializer::new);
        try {
            FALLBACK.validatePostLoad();
        } catch (ConfigData.ValidationException ignored) {
            // The defaults are always valid.
        }
    }

    public static AuraIntroConfig get() {
        ConfigHolder<AuraIntroConfig> current = holder;
        return current == null ? FALLBACK : current.getConfig();
    }

    public static void save() {
        ConfigHolder<AuraIntroConfig> current = holder;
        if (current != null) {
            current.save();
        }
    }
}
