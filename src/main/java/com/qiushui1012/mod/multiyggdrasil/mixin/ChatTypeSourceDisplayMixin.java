package com.qiushui1012.mod.multiyggdrasil.mixin;

import com.qiushui1012.mod.multiyggdrasil.display.LoginSourceDisplay;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ChatType;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChatType.class)
abstract class ChatTypeSourceDisplayMixin {
    // Both overloads bind directly through the RegistryAccess overload, not through each other.
    @Inject(
        method = "bind(Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/network/chat/ChatType$Bound;",
        at = @At("RETURN"), cancellable = true
    )
    private static void multiyggdrasil$labelPlayerChat(
        ResourceKey<ChatType> type, Entity entity, CallbackInfoReturnable<ChatType.Bound> callback
    ) {
        if (entity instanceof ServerPlayer player) {
            callback.setReturnValue(LoginSourceDisplay.forChat(player.getGameProfile(), callback.getReturnValue()));
        }
    }

    @Inject(
        method = "bind(Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/commands/CommandSourceStack;)Lnet/minecraft/network/chat/ChatType$Bound;",
        at = @At("RETURN"), cancellable = true
    )
    private static void multiyggdrasil$labelCommandChat(
        ResourceKey<ChatType> type, CommandSourceStack source, CallbackInfoReturnable<ChatType.Bound> callback
    ) {
        if (source.getEntity() instanceof ServerPlayer player) {
            callback.setReturnValue(LoginSourceDisplay.forChat(player.getGameProfile(), callback.getReturnValue()));
        }
    }
}
