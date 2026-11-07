package io.github.khiqwq.framerecorder.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import io.github.khiqwq.framerecorder.FrameRecorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 记录帧率限制器的睡眠，避免把限帧（如 Dynamic FPS 失焦/挂机降帧）误判为卡顿。
@Mixin(RenderSystem.class)
public abstract class FrameLimiterMixin {
    @Inject(method = "limitDisplayFPS", at = @At("HEAD"), require = 0)
    private static void framerecorder$enter(int fps, CallbackInfo ci) {
        FrameRecorder.limiterEnter(fps);
    }

    @Inject(method = "limitDisplayFPS", at = @At("RETURN"), require = 0)
    private static void framerecorder$exit(int fps, CallbackInfo ci) {
        FrameRecorder.limiterExit();
    }
}
