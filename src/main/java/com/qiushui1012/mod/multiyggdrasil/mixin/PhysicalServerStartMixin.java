package com.qiushui1012.mod.multiyggdrasil.mixin;

import com.mojang.authlib.GameProfileRepository;
import com.mojang.authlib.minecraft.SessionService;
import com.mojang.authlib.services.MinecraftServicesDiscoveryService;
import com.qiushui1012.mod.multiyggdrasil.auth.MultiYggdrasilAuthService;
import net.minecraft.server.Main;
import net.minecraft.server.Services;
import net.minecraft.server.players.CachedUserNameToIdResolver;
import net.minecraft.server.players.ProfileResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.io.File;

@Mixin(value = Main.class, priority = 50)
abstract class PhysicalServerStartMixin {
    @Redirect(
        method = "main",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/Services;create(Lcom/mojang/authlib/services/MinecraftServicesDiscoveryService;Ljava/io/File;)Lnet/minecraft/server/Services;"
        )
    )
    private static Services createBetter(MinecraftServicesDiscoveryService discoveryService, File file) {
        MultiYggdrasilAuthService authService = new MultiYggdrasilAuthService(discoveryService.getProxy());
        SessionService sessionService = authService.createMinecraftSessionService();
        GameProfileRepository profileRepository = authService.createProfileRepository();
        CachedUserNameToIdResolver nameToIdCache = new CachedUserNameToIdResolver(profileRepository, new File(file, "usercache.json"));
        ProfileResolver profileResolver = new ProfileResolver.Cached(sessionService, nameToIdCache);
        return new Services(sessionService, authService.getServicesKeySet(), profileRepository, nameToIdCache, profileResolver);
    }
}
