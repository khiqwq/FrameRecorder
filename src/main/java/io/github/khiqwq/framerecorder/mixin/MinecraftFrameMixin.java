package io.github.khiqwq.framerecorder.mixin;

import io.github.khiqwq.framerecorder.FrameRecorder;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftFrameMixin {
    @Inject(method = "runTick", at = @At("HEAD"))
    private void framerecorder$frame(boolean renderLevel, CallbackInfo ci) {
        FrameRecorder.frame();
    }
}
