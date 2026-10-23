package com.qiushui1012.mod.multiyggdrasil.mixin.client;

import com.mojang.authlib.minecraft.MinecraftProfileTextures;
import com.mojang.authlib.minecraft.SessionService;
import com.mojang.authlib.properties.Property;
import com.qiushui1012.mod.multiyggdrasil.client.ClientTextureTrust;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "net.minecraft.client.resources.SkinManager$1")
abstract class SkinTextureMixin {
    @Redirect(
        method = "lambda$load$0",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/authlib/minecraft/SessionService;unpackTextures(Lcom/mojang/authlib/properties/Property;)Lcom/mojang/authlib/minecraft/MinecraftProfileTextures;"
        )
    )
    private static MinecraftProfileTextures unpackTrustedTextures(SessionService original, Property property) {
        return ClientTextureTrust.unpackTextures(original, property);
    }
}
