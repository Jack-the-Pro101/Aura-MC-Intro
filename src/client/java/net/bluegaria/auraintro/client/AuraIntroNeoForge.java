package net.bluegaria.auraintro.client;

//? if neoforge {
/*import net.bluegaria.auraintro.client.compat.McCompat;
import net.bluegaria.auraintro.client.config.AuraIntroConfig;
import net.bluegaria.auraintro.client.video.FfmpegNativeLibrary;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

/^*
 * NeoForge client entrypoint.
 *
 * <p>NeoForge has no pre-launch hook: the mod is constructed while its own early loading screen is up,
 * which is still well before the vanilla loading screen the video appears on, so loading FFmpeg here
 * buys the same head start as Fabric's pre-launch entrypoint.</p>
 *
 * <p>The config screen is offered through NeoForge's mod list ("Config" button) - the counterpart of
 * the Mod Menu integration on Fabric.</p>
 ^/
@Mod(value = "auraintro", dist = Dist.CLIENT)
public class AuraIntroNeoForge {

    public AuraIntroNeoForge(IEventBus modBus, ModContainer container) {
        FfmpegNativeLibrary.warmUp();
        AuraIntroCommon.init();
        container.registerExtensionPoint(IConfigScreenFactory.class,
                (modContainer, parent) -> McCompat.configScreen(AuraIntroConfig.class, parent));
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class,
                event -> AuraIntroCommon.onClientTick(Minecraft.getInstance()));
        NeoForge.EVENT_BUS.addListener(GameShuttingDownEvent.class, event -> AuraIntroCommon.onShutdown());
    }
}
*///?}
