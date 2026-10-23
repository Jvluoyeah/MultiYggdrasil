package com.qiushui1012.mod.multiyggdrasil.skin;

import com.google.common.collect.ImmutableMultimap;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

/** Converts an authenticated source's skin into an unmodified, Mojang-signed texture property. */
public final class MineSkinBridge {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    private static final long POLL_INTERVAL_MILLIS = 1000;
    private final Path cacheFile;
    private final String apiKey;
    private final boolean enabled;
    private final int loginTimeoutMillis;
    private final int requestTimeoutMillis;
    private final URI apiRoot;
    private final PublicKey verificationKey;
    private final HttpClient client;
    private final Map<String, CachedTexture> cache = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Property>> flights = new ConcurrentHashMap<>();
    private final Map<String, PendingJob> pending = new ConcurrentHashMap<>();
    private final ReentrantLock requests = new ReentrantLock();
    private long nextRequestAt;

    private static final class Holder {
        private static final MineSkinBridge INSTANCE = production();
    }

    public static MineSkinBridge instance() {
        return Holder.INSTANCE;
    }

    private static MineSkinBridge production() {
        MineSkinConfig config = MineSkinConfig.load();
        return new MineSkinBridge(MineSkinConfig.configDirectory().resolve("multi-yggdrasil-skins-cache.json"),
            config.apiKey, config.enabled, config.loginTimeoutMillis, config.requestTimeoutMillis,
            URI.create("https://api.mineskin.org/v2/"), mojangKey());
    }

    // Package-private endpoint/key injection is exclusively a local HTTP/signature smoke-test seam.
    // Production never uses configured endpoints, source public keys, or network-fetched verification keys.
    MineSkinBridge(Path cacheFile, String apiKey, boolean enabled, int loginTimeoutMillis,
                   int requestTimeoutMillis, URI apiRoot, PublicKey verificationKey) {
        this.cacheFile = cacheFile;
        this.apiKey = apiKey;
        this.enabled = enabled;
        this.loginTimeoutMillis = Math.clamp(loginTimeoutMillis, 1000, 10000);
        this.requestTimeoutMillis = Math.clamp(requestTimeoutMillis, 250, this.loginTimeoutMillis);
        this.apiRoot = apiRoot;
        this.verificationKey = verificationKey;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(this.requestTimeoutMillis))
            .followRedirects(HttpClient.Redirect.NEVER).build();
        loadCache();
        if (apiKey.isBlank()) {
            LOGGER.warn("MineSkin API key is missing. Set {} (recommended), or apiKey in config/multi-yggdrasil-skins.json; enable the bridge with enabled=true. Original skins are preserved on cache misses.", MineSkinConfig.KEY_ENV);
        }
        if (!enabled) {
            LOGGER.info("MineSkin server-side skin bridge is disabled; set enabled=true in config/multi-yggdrasil-skins.json to enable it.");
        }
    }

    /** Preserves all non-texture properties, including authenticated-source metadata; never changes profile identity. */
    public PropertyMap bridge(PropertyMap original) {
        if (!enabled) return original;
        Property textures = original.get("textures").stream().findFirst().orElse(null);
        if (textures == null) return original;
        final SkinRequest skin;
        try {
            skin = skinRequest(textures);
            if (verify(textures, skin.model())) return original;
        } catch (RuntimeException e) {
            LOGGER.warn("MineSkin bridge skipped an invalid original skin texture payload; original textures preserved.");
            return original;
        }
        String key = cacheKey(skin);
        CachedTexture cached = cache.get(key);
        Property replacement = cached == null ? null : cached.property();
        if (replacement == null && !apiKey.isBlank()) {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(loginTimeoutMillis);
            CompletableFuture<Property> own = new CompletableFuture<>();
            CompletableFuture<Property> flight = flights.putIfAbsent(key, own);
            if (flight == null) {
                try {
                    // A previous flight may have populated the cache between our lookup and map insertion.
                    CachedTexture completed = cache.get(key);
                    replacement = completed == null ? generate(key, skin, deadline) : completed.property();
                    own.complete(replacement);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    own.complete(null);
                    LOGGER.warn("MineSkin skin generation interrupted; original textures preserved.");
                } catch (IOException | RuntimeException e) {
                    own.complete(null);
                    // Never log arbitrary API responses, skin URLs, request bodies, keys or exception messages.
                    LOGGER.warn("MineSkin skin bridge failed ({}); original textures preserved.", safeReason(e));
                } finally {
                    flights.remove(key, own);
                }
            } else {
                try {
                    replacement = flight.get(remaining(deadline), TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException | TimeoutException | BridgeFailure e) {
                    LOGGER.warn("MineSkin skin bridge exceeded this login's deadline waiting for an existing request; original textures preserved.");
                }
            }
        }
        if (replacement == null) return original;
        ImmutableMultimap.Builder<String, Property> properties = ImmutableMultimap.builder();
        original.forEach((name, property) -> {
            if (!"textures".equals(name)) properties.put(name, property);
        });
        properties.put("textures", replacement);
        return new PropertyMap(properties.build());
    }

    private Property generate(String key, SkinRequest skin, long deadline) throws IOException, InterruptedException {
        // A previous login may have timed out after enqueueing. Resume that job instead of creating another one.
        PendingJob job = pending.get(key);
        JsonObject response;
        if (job == null) {
            JsonObject body = new JsonObject();
            body.addProperty("url", skin.url());
            body.addProperty("variant", skin.model());
            body.addProperty("visibility", "unlisted");
            response = request("POST", "queue", body, deadline);
        } else {
            waitUntil(job.pollAt(), deadline);
            response = request("GET", "queue/" + pathSegment(job.id()), null, deadline);
        }
        while (true) {
            JsonObject info = object(response, "job");
            String status = string(info, "status");
            String id = string(info, "id");
            if ("failed".equals(status) || "unknown".equals(status)) {
                pending.remove(key);
                throw new BridgeFailure("queue job " + status);
            }
            JsonObject result = object(response, "skin");
            if (result != null) {
                Property property = property(result);
                if (!verify(property, skin.model())) {
                    pending.remove(key);
                    throw new BridgeFailure("returned texture failed Mojang signature, texture-host or model verification");
                }
                cache.put(key, new CachedTexture(skin.model(), property.value(), property.signature()));
                pending.remove(key);
                saveCache();
                return property;
            }
            if ("completed".equals(status)) {
                String resultId = string(info, "result");
                if (resultId == null || resultId.isBlank()) {
                    pending.remove(key);
                    throw new BridgeFailure("completed queue job has no result");
                }
                // JobInfo.result is a skin UUID, not a URL. Never follow response-provided links.
                JsonObject fetched = request("GET", "skins/" + pathSegment(resultId), null, deadline);
                Property property = property(object(fetched, "skin"));
                if (!verify(property, skin.model())) {
                    pending.remove(key);
                    throw new BridgeFailure("fetched texture failed Mojang signature, texture-host or model verification");
                }
                cache.put(key, new CachedTexture(skin.model(), property.value(), property.signature()));
                pending.remove(key);
                saveCache();
                return property;
            }
            if (id == null || id.isBlank() || !("waiting".equals(status) || "active".equals(status))) {
                pending.remove(key);
                throw new BridgeFailure("malformed queue response");
            }
            job = new PendingJob(id, System.currentTimeMillis() + POLL_INTERVAL_MILLIS);
            pending.put(key, job);
            waitUntil(job.pollAt(), deadline);
            response = request("GET", "queue/" + pathSegment(id), null, deadline);
        }
    }

    private JsonObject request(String method, String path, JsonObject body, long deadline) throws IOException, InterruptedException {
        if (!requests.tryLock(remaining(deadline), TimeUnit.NANOSECONDS)) throw new BridgeFailure("login deadline exceeded waiting for API queue");
        try {
            waitUntil(nextRequestAt, deadline);
            long timeout = Math.min(TimeUnit.MILLISECONDS.toNanos(requestTimeoutMillis), remaining(deadline));
            HttpRequest.Builder builder = HttpRequest.newBuilder(apiRoot.resolve(path)).timeout(Duration.ofNanos(timeout))
                .header("Authorization", "Bearer " + apiKey)
                .header("User-Agent", "MultiYggdrasil/MineSkinBridge")
                .header("Accept", "application/json");
            if ("POST".equals(method)) {
                builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)));
            } else {
                builder.GET();
            }
            CompletableFuture<HttpResponse<String>> future = client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            HttpResponse<String> response;
            try {
                response = future.get(Math.min(timeout, remaining(deadline)), TimeUnit.NANOSECONDS);
            } catch (TimeoutException | BridgeFailure e) {
                future.cancel(true);
                throw new BridgeFailure("HTTP request/login deadline exceeded; a known pending queue job is retained for a later login");
            } catch (ExecutionException e) {
                throw new BridgeFailure("HTTP request failed or timed out");
            } catch (InterruptedException e) {
                future.cancel(true);
                throw e;
            }
            JsonObject json = null;
            try {
                json = JsonParser.parseString(response.body()).getAsJsonObject();
            } catch (RuntimeException ignored) {
                // Process HTTP status/rate-limit headers even if a proxy returned a non-JSON error page.
            }
            updateRateLimit(response, json);
            int status = response.statusCode();
            if (status != 200 && status != 202) {
                throw new BridgeFailure(switch (status) {
                    case 401, 403 -> "API key rejected (HTTP " + status + "); check key validity and generation permission";
                    case 429 -> "API rate limited (HTTP 429); Retry-After/next-request cooldown retained";
                    default -> "API HTTP " + status;
                });
            }
            if (json == null) throw new BridgeFailure("API returned invalid JSON");
            if (json.has("success") && !json.get("success").getAsBoolean()) throw new BridgeFailure("API reported unsuccessful generation");
            return json;
        } finally {
            requests.unlock();
        }
    }

    private void updateRateLimit(HttpResponse<?> response, JsonObject json) {
        long now = System.currentTimeMillis();
        try {
            JsonObject rate = object(json, "rateLimit");
            JsonObject next = object(rate, "next");
            if (next != null) {
                if (next.has("absolute")) nextRequestAt = Math.max(nextRequestAt, next.get("absolute").getAsLong());
                if (next.has("relative")) nextRequestAt = Math.max(nextRequestAt, now + Math.max(0, next.get("relative").getAsLong()));
            }
        } catch (RuntimeException ignored) {
            // A malformed advisory field cannot bypass a valid Retry-After header.
        }
        String retry = response.headers().firstValue("Retry-After").orElse(null);
        if (retry != null) {
            try {
                long delayMillis = Math.min(TimeUnit.DAYS.toMillis(1), Math.max(0, Long.parseLong(retry)) * 1000);
                nextRequestAt = Math.max(nextRequestAt, now + delayMillis);
            } catch (NumberFormatException e) {
                try {
                    nextRequestAt = Math.max(nextRequestAt, ZonedDateTime.parse(retry, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli());
                } catch (RuntimeException ignored) {
                }
            }
        } else if (response.statusCode() == 429) {
            nextRequestAt = Math.max(nextRequestAt, now + POLL_INTERVAL_MILLIS);
        }
    }

    private boolean verify(Property property, String expectedModel) {
        if (property == null || !"textures".equals(property.name()) || !property.hasSignature()) return false;
        try {
            Signature signature = Signature.getInstance("SHA1withRSA");
            signature.initVerify(verificationKey);
            signature.update(property.value().getBytes(StandardCharsets.UTF_8));
            if (!signature.verify(Base64.getDecoder().decode(property.signature()))) return false;
            JsonObject payload = payload(property);
            JsonObject textures = object(payload, "textures");
            JsonObject skin = object(textures, "SKIN");
            if (skin == null || !expectedModel.equals(model(skin))) return false;
            for (Map.Entry<String, JsonElement> entry : textures.entrySet()) {
                URI url = URI.create(entry.getValue().getAsJsonObject().get("url").getAsString());
                if (!("https".equalsIgnoreCase(url.getScheme()) || "http".equalsIgnoreCase(url.getScheme()))
                    || !"textures.minecraft.net".equalsIgnoreCase(url.getHost()) || url.getRawUserInfo() != null
                    || url.getPort() != -1 || url.getRawQuery() != null || url.getRawFragment() != null
                    || url.getPath() == null || !url.getPath().matches("/texture/[a-fA-F0-9]+")) return false;
            }
            return true;
        } catch (GeneralSecurityException | RuntimeException e) {
            return false;
        }
    }

    private static SkinRequest skinRequest(Property property) {
        JsonObject skin = object(object(payload(property), "textures"), "SKIN");
        String url = string(skin, "url");
        URI uri = URI.create(url);
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
            || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Invalid original texture URL");
        }
        return new SkinRequest(url, model(skin));
    }

    private static String model(JsonObject skin) {
        String model = string(object(skin, "metadata"), "model");
        if (model == null || "default".equals(model) || "classic".equals(model)) return "classic";
        if ("slim".equals(model)) return "slim";
        throw new IllegalArgumentException("Invalid texture model");
    }

    private static JsonObject payload(Property property) {
        return JsonParser.parseString(new String(Base64.getDecoder().decode(property.value()), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static Property property(JsonObject skin) throws BridgeFailure {
        JsonObject data = object(object(skin, "texture"), "data");
        String value = string(data, "value");
        String signature = string(data, "signature");
        if (value == null || signature == null) throw new BridgeFailure("API response is missing signed texture data");
        return new Property("textures", value, signature);
    }

    private static String cacheKey(SkinRequest skin) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((skin.url() + "\n" + skin.model()).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private void loadCache() {
        if (!Files.exists(cacheFile)) return;
        try (Reader reader = Files.newBufferedReader(cacheFile, StandardCharsets.UTF_8)) {
            JsonObject entries = JsonParser.parseReader(reader).getAsJsonObject();
            int rejected = 0;
            for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
                try {
                    CachedTexture texture = GSON.fromJson(entry.getValue(), CachedTexture.class);
                    if (entry.getKey().matches("[a-f0-9]{64}") && texture != null
                        && ("classic".equals(texture.model()) || "slim".equals(texture.model()))
                        && verify(texture.property(), texture.model())) {
                        cache.put(entry.getKey(), texture);
                    } else {
                        rejected++;
                    }
                } catch (RuntimeException e) {
                    rejected++;
                }
            }
            if (rejected > 0) LOGGER.warn("MineSkin ignored {} cached textures failing Mojang signature, host or model verification.", rejected);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Cannot read MineSkin texture cache; only verified newly generated textures will be used.");
        }
    }

    private synchronized void saveCache() {
        Path temporary = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
        try {
            Files.createDirectories(cacheFile.toAbsolutePath().getParent());
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                GSON.toJson(cache, writer);
            }
            try {
                Files.move(temporary, cacheFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, cacheFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.warn("Cannot persist MineSkin texture cache; the verified skin remains cached for this server process.");
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
            }
        }
    }

    private static PublicKey mojangKey() {
        try (InputStream stream = MineSkinBridge.class.getResourceAsStream("/yggdrasil_session_pubkey.der")) {
            if (stream == null) throw new IOException("Bundled Mojang key missing");
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(stream.readAllBytes()));
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("Cannot load bundled Mojang texture verification key", e);
        }
    }

    private static JsonObject object(JsonObject parent, String name) {
        if (parent == null || !parent.has(name) || parent.get(name).isJsonNull()) return null;
        return parent.getAsJsonObject(name);
    }

    private static String string(JsonObject parent, String name) {
        if (parent == null || !parent.has(name) || parent.get(name).isJsonNull()) return null;
        return parent.get(name).getAsString();
    }

    private static String pathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static long remaining(long deadline) throws BridgeFailure {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new BridgeFailure("login deadline exceeded; pending queue job retained for a later login");
        return remaining;
    }

    private static void waitUntil(long timestamp, long deadline) throws InterruptedException, BridgeFailure {
        long delay = timestamp - System.currentTimeMillis();
        if (delay <= 0) return;
        long remaining = remaining(deadline);
        if (TimeUnit.MILLISECONDS.toNanos(delay) >= remaining) throw new BridgeFailure("queue/rate-limit wait exceeds login deadline; pending job retained");
        TimeUnit.MILLISECONDS.sleep(delay);
    }

    private static String safeReason(Exception e) {
        return e instanceof BridgeFailure ? e.getMessage() : "invalid API response";
    }

    private record SkinRequest(String url, String model) {}
    private record PendingJob(String id, long pollAt) {}
    private record CachedTexture(String model, String value, String signature) {
        Property property() {
            return new Property("textures", value, signature);
        }
    }
    private static final class BridgeFailure extends IOException {
        BridgeFailure(String reason) {
            super(reason);
        }
    }
}
