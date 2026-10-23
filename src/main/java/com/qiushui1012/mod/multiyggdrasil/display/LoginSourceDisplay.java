package com.qiushui1012.mod.multiyggdrasil.display;

import com.google.common.collect.Iterables;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.qiushui1012.mod.multiyggdrasil.auth.AuthenticatedSource;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;

public final class LoginSourceDisplay {
    private LoginSourceDisplay() {
    }

    public static Component prepend(GameProfile profile, Component displayName) {
        Property source = source(profile);
        if (source == null) {
            return displayName;
        }
        // Keep the existing component (including team styling and custom formatting) intact.
        return Component.literal("[" + source.value() + "] ").append(displayName);
    }

    /** A null custom name normally lets the client apply its scoreboard team formatting. */
    public static Component tabName(GameProfile profile, PlayerTeam team, Component customName) {
        Property source = source(profile);
        if (source == null) {
            return customName;
        }
        Component base = customName != null ? customName
            : PlayerTeam.formatNameForTeam(team, Component.literal(profile.name()));
        return Component.literal("[" + source.value() + "] ").append(base);
    }

    /** Decorate only the unsigned chat-type sender name, never the signed message body. */
    public static ChatType.Bound forChat(GameProfile profile, ChatType.Bound bound) {
        Component name = prepend(profile, bound.name());
        return name == bound.name() ? bound : new ChatType.Bound(bound.chatType(), name, bound.targetName());
    }

    public static void refreshTab(MinecraftServer server, String playerName) {
        ServerPlayer player = server.getPlayerList().getPlayerByName(playerName);
        if (player != null && source(player.getGameProfile()) != null) {
            server.getPlayerList().broadcastAll(new ClientboundPlayerInfoUpdatePacket(
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME, player
            ));
        }
    }

    private static Property source(GameProfile profile) {
        return Iterables.getFirst(profile.properties().get(AuthenticatedSource.PROPERTY_NAME), null);
    }
}
