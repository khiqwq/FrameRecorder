package io.github.khiqwq.framerecorder;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FrameRecorderMod implements ClientModInitializer {
    static final Logger LOGGER = LoggerFactory.getLogger("FrameRecorder");

    @Override
    public void onInitializeClient() {
        // 连接事件可能不在渲染线程，统一切回渲染线程处理
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> {
            var server = client.getCurrentServer();
            FrameRecorder.start(server != null ? server.ip : client.isLocalServer() ? "singleplayer" : "unknown");
        }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(FrameRecorder::stop));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> FrameRecorder.stop());
    }
}
