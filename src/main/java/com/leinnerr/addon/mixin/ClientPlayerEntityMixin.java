package com.leinnerr.addon.mixin;

import com.leinnerr.addon.modules.Phase;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayerEntity.class)
public class ClientPlayerEntityMixin {
    @Inject(method = "pushOutOfBlocks", at = @At("HEAD"), cancellable = true)
    private void leinnerr$onPushOutOfBlocks(double x, double z, CallbackInfo ci) {
        Phase phase = Modules.get().get(Phase.class);
        if (phase != null && phase.isActive()) ci.cancel();
    }
}
