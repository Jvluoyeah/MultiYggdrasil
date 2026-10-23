package com.qiushui1012.mod.multiyggdrasil.skin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class MineSkinConfig {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    static final String KEY_ENV = "MULTIYGGDRASIL_MINESKIN_API_KEY";

    boolean enabled = false;
    String apiKey = "";
    int loginTimeoutMillis = 8000;
    int requestTimeoutMillis = 3000;

    static MineSkinConfig load() {
        Path path = configDirectory().resolve("multi-yggdrasil-skins.json");
        MineSkinConfig config = new MineSkinConfig();
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                if (json.has("enabled")) config.enabled = json.get("enabled").getAsBoolean();
                if (json.has("apiKey")) config.apiKey = json.get("apiKey").getAsString();
                if (json.has("loginTimeoutMillis")) config.loginTimeoutMillis = json.get("loginTimeoutMillis").getAsInt();
                if (json.has("requestTimeoutMillis")) config.requestTimeoutMillis = json.get("requestTimeoutMillis").getAsInt();
            } catch (IOException | RuntimeException e) {
                // Do not include JSON/parser exception text: the file may contain an API key.
                LOGGER.warn("Cannot read multi-yggdrasil-skins.json; MineSkin bridge disabled. Check the JSON configuration.");
                config = new MineSkinConfig();
            }
        } else {
            try {
                Files.createDirectories(path.getParent());
                try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                    GSON.toJson(config, writer);
                }
            } catch (IOException e) {
                LOGGER.warn("Cannot create multi-yggdrasil-skins.json; MineSkin bridge remains disabled.");
            }
        }
        String environmentKey = System.getenv(KEY_ENV);
        config.apiKey = environmentKey != null && !environmentKey.isBlank() ? environmentKey.trim() : config.apiKey.trim();
        config.loginTimeoutMillis = Math.clamp(config.loginTimeoutMillis, 1000, 10000);
        config.requestTimeoutMillis = Math.clamp(config.requestTimeoutMillis, 250, config.loginTimeoutMillis);
        return config;
    }

    static Path configDirectory() {
        return FabricLoader.getInstance().getConfigDir();
    }
}
