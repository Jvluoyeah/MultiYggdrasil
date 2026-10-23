package com.qiushui1012.mod.multiyggdrasil.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.authlib.SignatureState;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.minecraft.MinecraftProfileTextures;
import com.mojang.authlib.minecraft.SessionService;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.services.response.MinecraftTexturesPayload;
import com.mojang.util.UUIDTypeAdapter;
import com.mojang.logging.LogUtils;
import com.qiushui1012.mod.multiyggdrasil.auth.TextureUrlChecker;
import com.qiushui1012.mod.multiyggdrasil.config.YggdrasilConfig;
import com.qiushui1012.mod.multiyggdrasil.source.BaseYggdrasilSource;
import com.qiushui1012.mod.multiyggdrasil.source.BlessingSkinYggdrasilSource;
import com.qiushui1012.mod.multiyggdrasil.util.ParseUtil;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.IDN;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Additional texture authorities selected locally, never supplied by a game server. */
public final class ClientTextureTrust {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder()
        .registerTypeAdapter(UUID.class, new UUIDTypeAdapter()).create();
    private static final int MAX_METADATA_BYTES = 1024 * 1024;
    private final List<Authority> authorities;

    private ClientTextureTrust(List<Authority> authorities) {
        this.authorities = List.copyOf(authorities);
    }

    private static final class ConfiguredTrust {
        // The SkinManager redirect runs on unpackSkinTextures, not the render thread.
        private static final ClientTextureTrust INSTANCE = load(YggdrasilConfig.loadForClient());
    }

    public static MinecraftProfileTextures unpackTextures(SessionService original, Property property) {
        MinecraftProfileTextures vanilla = original.unpackTextures(property);
        if (vanilla.signatureState() == SignatureState.SIGNED || !property.hasSignature()) {
            return vanilla;
        }
        MinecraftProfileTextures trusted = ConfiguredTrust.INSTANCE.unpack(property);
        return trusted == MinecraftProfileTextures.EMPTY ? vanilla : trusted;
    }

    public static ClientTextureTrust load(YggdrasilConfig config) {
        List<Authority> authorities = new ArrayList<>();
        for (BaseYggdrasilSource source : config.sources()) {
            if (!(source instanceof BlessingSkinYggdrasilSource blessing)) continue;
            try {
                authorities.add(loadAuthority(blessing));
                LOGGER.info("Loaded client texture authority {}", source.getName());
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Cannot trust client textures from {}: {}", source.getName(), e.toString());
            }
        }
        return new ClientTextureTrust(authorities);
    }

    private static Authority loadAuthority(BlessingSkinYggdrasilSource source) throws IOException {
        URI root = URI.create(source.getApiRoot());
        if (!"https".equalsIgnoreCase(root.getScheme()) || root.getHost() == null || root.getUserInfo() != null) {
            throw new IOException("Texture authority metadata must use HTTPS without user info");
        }
        HttpURLConnection connection = (HttpURLConnection) root.toURL().openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        // A redirect must not replace the locally selected authority (or downgrade TLS).
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/json");
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("Metadata HTTP status " + connection.getResponseCode());
            }
            final JsonObject metadata;
            try (InputStream input = connection.getInputStream()) {
                byte[] bytes = input.readNBytes(MAX_METADATA_BYTES + 1);
                if (bytes.length > MAX_METADATA_BYTES) throw new IOException("Metadata is too large");
                metadata = GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), JsonObject.class);
            }
            PublicKey key = ParseUtil.parseSignaturePublicKey(metadata.get("signaturePublickey").getAsString());
            JsonArray configuredDomains = metadata.getAsJsonArray("skinDomains");
            List<String> domains = new ArrayList<>();
            for (JsonElement domain : configuredDomains) {
                String value = domain.getAsString();
                boolean suffix = value.startsWith(".");
                String host = canonicalHost(suffix ? value.substring(1) : value);
                domains.add(suffix ? "." + host : host);
            }
            if (domains.isEmpty()) throw new IOException("Metadata has no skinDomains");
            return new Authority(key, List.copyOf(domains));
        } finally {
            connection.disconnect();
        }
    }

    /** Returns no textures unless one authority verifies both signature and all texture hosts. */
    public MinecraftProfileTextures unpack(Property property) {
        if (!"textures".equals(property.name()) || !property.hasSignature()) return MinecraftProfileTextures.EMPTY;
        try {
            byte[] signature = Base64.getDecoder().decode(property.signature());
            byte[] value = property.value().getBytes(StandardCharsets.UTF_8);
            for (Authority authority : authorities) {
                if (!authority.verifies(value, signature)) continue;
                MinecraftTexturesPayload payload = GSON.fromJson(
                    new String(Base64.getDecoder().decode(property.value()), StandardCharsets.UTF_8),
                    MinecraftTexturesPayload.class
                );
                if (payload == null || payload.textures() == null || payload.textures().isEmpty()) {
                    return MinecraftProfileTextures.EMPTY;
                }
                Map<MinecraftProfileTexture.Type, MinecraftProfileTexture> textures = payload.textures();
                boolean allowed = true;
                for (MinecraftProfileTexture texture : textures.values()) {
                    if (texture == null || !authority.allows(texture.getUrl())) {
                        allowed = false;
                        break;
                    }
                }
                if (allowed) {
                    return new MinecraftProfileTextures(
                        textures.get(MinecraftProfileTexture.Type.SKIN),
                        textures.get(MinecraftProfileTexture.Type.CAPE),
                        textures.get(MinecraftProfileTexture.Type.ELYTRA),
                        SignatureState.SIGNED
                    );
                }
            }
        } catch (RuntimeException e) {
            LOGGER.debug("Rejected malformed client texture property", e);
        }
        return MinecraftProfileTextures.EMPTY;
    }

    private static String canonicalHost(String host) {
        String ascii = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        if (ascii.isEmpty() || ascii.startsWith(".") || ascii.endsWith(".")) {
            throw new IllegalArgumentException("Invalid texture host");
        }
        return ascii;
    }

    private record Authority(PublicKey key, List<String> domains) {
        private boolean verifies(byte[] value, byte[] encodedSignature) {
            try {
                Signature verifier = Signature.getInstance("SHA1withRSA");
                verifier.initVerify(key);
                verifier.update(value);
                return verifier.verify(encodedSignature);
            } catch (GeneralSecurityException e) {
                return false;
            }
        }

        private boolean allows(String url) {
            if (url == null || !TextureUrlChecker.isAllowedTextureDomain(url)) return false;
            URI uri = URI.create(url);
            if (uri.getUserInfo() != null) return false;
            String host = canonicalHost(uri.getHost());
            for (String domain : domains) {
                if (domain.startsWith(".")) {
                    if (host.equals(domain.substring(1)) || host.endsWith(domain)) return true;
                } else if (host.equals(domain)) {
                    return true;
                }
            }
            return false;
        }
    }
}
