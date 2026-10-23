package com.qiushui1012.mod.multiyggdrasil.mixin;

import com.qiushui1012.mod.multiyggdrasil.display.LoginSourceDisplay;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
abstract class ServerPlayerTabSourceDisplayMixin {
    @Inject(method = "getTabListDisplayName", at = @At("RETURN"), cancellable = true)
    private void multiyggdrasil$displayLoginSource(CallbackInfoReturnable<Component> callback) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        callback.setReturnValue(LoginSourceDisplay.tabName(
            player.getGameProfile(), player.getTeam(), callback.getReturnValue()
        ));
    }
}
