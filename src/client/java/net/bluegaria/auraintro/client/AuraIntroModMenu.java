package net.bluegaria.auraintro.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.bluegaria.auraintro.client.compat.McCompat;
import net.bluegaria.auraintro.client.config.AuraIntroConfig;

/**
 * Mod Menu integration: exposes the Cloth Config screen through the standard "Config" button.
 */
public class AuraIntroModMenu implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> McCompat.configScreen(AuraIntroConfig.class, parent);
    }
}
