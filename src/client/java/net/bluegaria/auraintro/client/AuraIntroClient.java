package net.bluegaria.auraintro.client;

//? if fabric {
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/** Fabric client entrypoint. */
public class AuraIntroClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        AuraIntroCommon.init();
        ClientTickEvents.END_CLIENT_TICK.register(AuraIntroCommon::onClientTick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> AuraIntroCommon.onShutdown());
    }
}
//?}
