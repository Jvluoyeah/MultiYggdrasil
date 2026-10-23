package com.qiushui1012.mod.multiyggdrasil.mixin;

import com.qiushui1012.mod.multiyggdrasil.display.LoginSourceDisplay;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.world.scores.PlayerTeam;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerScoreboard.class)
abstract class ServerScoreboardSourceDisplayMixin {
    @Shadow
    @Final
    private MinecraftServer server;

    @Inject(method = "addPlayerToTeam", at = @At("RETURN"))
    private void multiyggdrasil$refreshJoinedPlayer(
        String playerName, PlayerTeam team, CallbackInfoReturnable<Boolean> callback
    ) {
        if (callback.getReturnValueZ()) {
            LoginSourceDisplay.refreshTab(server, playerName);
        }
    }

    @Inject(method = "removePlayerFromTeam", at = @At("RETURN"))
    private void multiyggdrasil$refreshDepartedPlayer(String playerName, PlayerTeam team, CallbackInfo callback) {
        LoginSourceDisplay.refreshTab(server, playerName);
    }

    // Explicit Tab names do not automatically follow client-side scoreboard updates.
    // These callbacks run after the vanilla team packet and never alter the team itself.
    @Inject(method = {"onTeamChanged", "onTeamRemoved"}, at = @At("RETURN"))
    private void multiyggdrasil$refreshTeamNames(PlayerTeam team, CallbackInfo callback) {
        for (String playerName : team.getPlayers()) {
            LoginSourceDisplay.refreshTab(server, playerName);
        }
    }
}
