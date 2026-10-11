package nova;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import nova.core.Config;
import nova.core.ModuleManager;

public class NovaClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ModuleManager.init();
        Config.load();
        ClientTickEvents.END_CLIENT_TICK.register(c -> ModuleManager.tick());
        HudRenderCallback.EVENT.register((ctx, tc) -> ModuleManager.hud(ctx));
        ClientLifecycleEvents.CLIENT_STOPPING.register(c -> Config.save());
    }
}
