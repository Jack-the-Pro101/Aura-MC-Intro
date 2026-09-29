package net.bluegaria.titlescreen.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.bluegaria.titlescreen.client.compat.McCompat;
import net.bluegaria.titlescreen.client.config.TitlescreenConfig;

/**
 * Mod Menu integration: exposes the Cloth Config screen through the standard "Config" button.
 */
public class TitlescreenModMenu implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> McCompat.configScreen(TitlescreenConfig.class, parent);
    }
}
