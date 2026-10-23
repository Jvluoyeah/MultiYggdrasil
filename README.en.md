<div align="center">

# MultiYggdrasil

[![GitHub Release](https://img.shields.io/github/release/QiuShui1012/MultiYggdrasil.svg)](https://github.com/QiuShui1012/MultiYggdrasil/releases/)
[![Github License](https://img.shields.io/github/license/QiuShui1012/MultiYggdrasil)](https://github.com/QiuShui1012/MultiYggdrasil/blob/neoforge/LICENSE.txt)
[![QQ Group](https://img.shields.io/badge/QQ%20group-108917413-yellow)](https://qm.qq.com/q/XJGuYx9W6u)

[简体中文](README.md) | **English**

</div>

## Summary

MultiYggdrasil is a Forge | Fabric | NeoForge mod.  
It allows you to set multiple Yggdrasil API sources, including the official and other external authentication servers.  
Config design is inspired by [MultiLogin](https://github.com/CaaMoe/MultiLogin),
and some codes are borrowed from [authlib-injector](https://github.com/yushijinhun/authlib-injector/) under the AGPL-3.0 license.  
~~patchwork~~

## Deploy

Same as other mods, there are only three steps:
1. Download mod from [CurseForge](https://curseforge.com/minecraft/mc-mods/multiyggdrasil) | [Modrinth](https://modrinth.com/mod/multiyggdrasil/)
2. Throw it into `/mods`
3. Launch the server

### 26.3 Fabric: external skins and login-source labels

- The server labels **Tab entries and chat sender names** with the actual successful source, including official sources: `[MojangOfficial]`, `[LittleSkin]`, etc. Labels use section names from `multi-yggdrasil.ini`. Reconnect after upgrading to obtain new authentication metadata.
- Labels never modify the GameProfile name, UUID, saved identity, above-head name or scoreboard membership. Existing custom Tab names and team styling are retained; team changes refresh Tab. Existing Lobby-style head prefixes remain unchanged.

#### No client changes: MineSkin signature bridge

The server can submit skin-site skins to [MineSkin](https://docs.mineskin.org/docs/mineskin-api/mineskin-api) and receive Mojang-signed textures accepted by vanilla clients. Viewers need no mod. **A MineSkin API key is required; the bridge is disabled by default.** It cannot forge signatures locally and does not guarantee preservation of external capes.

Server startup creates `config/multi-yggdrasil-skins.json` with the bridge disabled, without requiring any player login. Edit it to:

```json
{
  "enabled": true,
  "apiKey": "",
  "loginTimeoutMillis": 8000,
  "requestTimeoutMillis": 3000
}
```

Prefer the server-process environment variable `MULTIYGGDRASIL_MINESKIN_API_KEY`, which overrides `apiKey` in the file. Supplying a key alone does not enable the bridge: `enabled` must also be true. Restart after configuration changes; never commit API keys.

- The first request submits the original skin URL and classic/slim model to the third party, using `unlisted` visibility. Only authenticated `BLESSING_SKIN` players enter this path.
- Returned properties must pass independent Mojang signature, `textures.minecraft.net` URL and model verification. The signed payload is copied unchanged; player identity and source labels are preserved.
- Verified results persist in `config/multi-yggdrasil-skins-cache.json`, keyed by original URL and model, and are verified again on reload. Content changes at an unchanged URL do not invalidate the cache; stop the server and remove its cache entry to regenerate.
- Initial generation can queue. The default login deadline is 8 seconds, configurable within 1–10 seconds. Timeouts, rate limits, missing keys and verification failures preserve the original texture and log the issue; **vanilla skin visibility is not guaranteed for that login**. Known pending jobs survive within the current server process and resume on reconnect. Skins are not hot-refreshed in an existing session.

#### Optional: direct client trust

Without MineSkin, viewers can still install this mod and configure trusted `BLESSING_SKIN` sources in their client's `config/multi-yggdrasil.ini` (defaults apply when absent). A source's HTTPS metadata must supply both the signing key and matching `skinDomains`; HTTP metadata and redirects are rejected. This avoids conversion but is not server-only.

Structure: `config` / `source` describe authorities, `auth` authenticates and stamps the source, `skin` handles MineSkin jobs and verified caches, and `display` / server mixins label Tab and chat. `client/ClientTextureTrust` remains an optional client-side trust path.

## Configuration

A template:
```ini
# This config is located at config/multi-yggdrasil.ini
[SomeRandomMirror]                     # Source name, also used for login-source labels.
type = OFFICIAL                        # The type, now has 2 types, "OFFICIAL" and "BLESSING_SKIN".
authHost = https://a.random.mirror     # |- The specific value of "OFFICIAL" type.
accountsHost = https://a.random.mirror # |  Requirements in below.
sessionHost = https://a.random.mirror  # |
servicesHost = https://a.random.mirror # |
profilesHost = https://a.random.mirror # |
ordinal = 0                            # The ordinal, decided the order of use for this source

[MojangOfficial]
type = OFFICIAL                        # When using "OFFICIAL" type, there can be no host-like property,
ordinal = 1                            # and it will use the official API.

[LittleSkin]
type = BLESSING_SKIN                   # ↙ The specific value of "BLESSING_SKIN" type.
apiRoot = https://littleskin.cn/api/yggdrasil/
ordinal = 2
```

The requirements of these host-like specific values for “OFFICIAL” type are depending on the MC version, see the table below:

| authlib       | Minecraft      | authHost | accountsHost | sessionHost | servicesHost | profilesHost |
|:--------------|:---------------|:--------:|:------------:|:-----------:|:------------:|:------------:|
| 1.5.25~1.6.25 | 1.14.4~1.16.3  |    ✅     |      ✅       |      ✅      |      ❌       |      ❌       |
| 2.1.28~4.0.43 | 1.16.4~1.20.1  |    ✅     |      ✅       |      ✅      |      ✅       |      ❌       |
| 5.0.47        | 1.20.2         |    ❌     |      ✅       |      ✅      |      ✅       |      ❌       |
| 6.0.52~6.0.58 | 1.20.3~1.21.8  |    ❌     |      ❌       |      ✅      |      ✅       |      ❌       |
| 7.0.61~9.0.75 | 1.21.9~26.2    |    ❌     |      ❌       |      ✅      |      ✅       |      ✅       |
| 10.0.77+      | 26.3+          |    ❌     |      ❌       |      ✅      |      ✅       |      ✅       |

## Version Supporting
| Minecraft     | Forge | Fabric | NeoForge |
|:--------------|:-----:|:------:|:--------:|
| 1.14.4-       |   ❌   |   ❌    |    ❌     |
| 1.14.4~1.19.4 |   ✅   |   ✅    |    ❌     |
| 1.20.1~26.3   |   ✅   |   ✅    |    ✅     |
