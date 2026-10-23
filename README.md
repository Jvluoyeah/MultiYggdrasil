<div align="center">

# MultiYggdrasil

[![GitHub Release](https://img.shields.io/github/release/QiuShui1012/MultiYggdrasil.svg)](https://github.com/QiuShui1012/MultiYggdrasil/releases/)
[![Github License](https://img.shields.io/github/license/QiuShui1012/MultiYggdrasil)](https://github.com/QiuShui1012/MultiYggdrasil/blob/neoforge/LICENSE.txt)
[![QQ Group](https://img.shields.io/badge/QQ%20group-108917413-yellow)](https://qm.qq.com/q/XJGuYx9W6u)

**简体中文** | [English](README.en.md)

</div>

## 概述

MultiYggdrasil 是一个运行于 Forge | Fabric | NeoForge 的模组。  
它允许您设置多个Yggdrasil API来源，包括正版和其它外置登录。  
配置设计参考了[MultiLogin](https://github.com/CaaMoe/MultiLogin)，部分代码来自[authlib-injector](https://github.com/yushijinhun/authlib-injector/)，遵循APGL-3.0版权。  
~~缝合怪~~

## 安装

与其它大部分模组一致，仅需三步：
1. 从 [CurseForge](https://curseforge.com/minecraft/mc-mods/multiyggdrasil) | [Modrinth](https://modrinth.com/mod/multiyggdrasil/) 下载模组
2. 放入 `/mods` 文件夹
3. 启动游戏

### 26.3 Fabric：外置皮肤与登入方式标识

- 服务端负责多来源认证，并在 **Tab 列表和聊天发送者名称**中显示实际认证来源，例如 `[MojangOfficial]`、`[LittleSkin]`、`[ElyBy]`。名称取自 `multi-yggdrasil.ini` 的配置节；将 `[MojangOfficial]` 改为 `[正版]` 可显示中文标识。旧版本需要重新登录后才有新标记。
- 标识不修改 GameProfile 名称、UUID、登录名、存档身份、头顶名称或计分板队伍。保留原有自定义 Tab 名称与队伍样式，队伍变化会同步刷新 Tab；现有 `Lobby` 等头顶前缀保持不变。

#### 不改客户端：MineSkin 签名桥接

服务端可将皮肤站皮肤提交至 [MineSkin](https://docs.mineskin.org/docs/mineskin-api/mineskin-api)，获得原版客户端接受的 Mojang 签名纹理。观看者无需安装本模组。**此功能需要 MineSkin API Key，默认关闭**；它不会在本地伪造签名，也不保证保留外置披风。

服务器启动时会自动生成默认关闭的 `config/multi-yggdrasil-skins.json`，无需玩家先登录。修改为：

```json
{
  "enabled": true,
  "apiKey": "",
  "loginTimeoutMillis": 8000,
  "requestTimeoutMillis": 3000
}
```

建议通过服务器进程环境变量 `MULTIYGGDRASIL_MINESKIN_API_KEY` 配置密钥；也可填写 `apiKey`，环境变量优先。仅设置密钥不会自动开启功能，仍需 `enabled: true`。修改后重启服务器；勿提交包含密钥的配置文件。

- 首次使用某张皮肤时提交其 URL 与 classic/slim 模型，MineSkin 可访问该皮肤；请求使用 `unlisted` 可见性，仍会向第三方共享皮肤。只有皮肤站认证成功的玩家进入桥接路径。
- 验证返回纹理的 Mojang 签名、`textures.minecraft.net` 地址及模型后，原样替换 `textures` 属性，不修改签名内的 JSON 或玩家身份。
- 按原皮肤 URL 与模型缓存到 `config/multi-yggdrasil-skins-cache.json`，重启后重新验证再使用。相同 URL 内容变化不会自动刷新；停服删除对应缓存条目可重新生成。
- 首次生成可能排队。登录等待默认最多 8 秒（配置限制为 1–10 秒）；超时、限流、缺少密钥或签名校验失败时记录日志并保留原纹理，**本次登录不保证原版客户端能显示外置皮肤**。已知未完成任务在当前服务端进程内保留，下次重连继续查询；不会在当前会话中热刷新皮肤。

#### 可选：客户端直接信任皮肤站

不使用 MineSkin 时，观看者仍可安装本模组，在客户端 `config/multi-yggdrasil.ini` 配置可信 `BLESSING_SKIN` 来源（缺省使用默认来源）。客户端只接受该来源 HTTPS 元数据发布的公钥签名和 `skinDomains`，不接受 HTTP 元数据或重定向；此方式不需要第三方转换，但不是纯服务端方案。

结构：`config` / `source` 描述认证来源，`auth` 执行认证并标记成功来源；`skin` 处理 MineSkin 队列与签名缓存；`display` 与服务端 mixin 处理 Tab、聊天标识；`client/ClientTextureTrust` 是可选的客户端皮肤站信任路径。

## 配置

一个模板：
```ini
# 该配置路径位于 config/multi-yggdrasil.ini
[SomeRandomMirror]                     # *来源名称，也用于登入方式标识
type = OFFICIAL                        # *类型，目前支持“OFFICIAL”和“BLESSING_SKIN”
authHost = https://a.random.mirror     # “OFFICIAL”类型的特定值，需求见下表
accountsHost = https://a.random.mirror # “OFFICIAL”类型的特定值，需求见下表
sessionHost = https://a.random.mirror  # “OFFICIAL”类型的特定值，需求见下表
servicesHost = https://a.random.mirror # “OFFICIAL”类型的特定值，需求见下表
profilesHost = https://a.random.mirror # “OFFICIAL”类型的特定值，需求见下表
ordinal = 0                            # *序号，决定了该来源的使用顺序

[MojangOfficial]
type = OFFICIAL                        # “OFFICIAL”类型可以没有Host属性值，此时会使用官方API
ordinal = 1

[LittleSkin]
type = BLESSING_SKIN                   # ↙ *“BLESSING_SKIN”类型的特定值
apiRoot = https://littleskin.cn/api/yggdrasil/  
ordinal = 2
```

“OFFICIAL”类型的特定值的必需与否与MC版本有关，详见下表：

| authlib       | Minecraft     | authHost | accountsHost | sessionHost | servicesHost | profilesHost |
|:--------------|:--------------|:--------:|:------------:|:-----------:|:------------:|:------------:|
| 1.5.25~1.6.25 | 1.14.4~1.16.3 |    ✅     |      ✅       |      ✅      |      ❌       |      ❌       |
| 2.1.28~4.0.43 | 1.16.4~1.20.1 |    ✅     |      ✅       |      ✅      |      ✅       |      ❌       |
| 5.0.47        | 1.20.2        |    ❌     |      ✅       |      ✅      |      ✅       |      ❌       |
| 6.0.52~6.0.58 | 1.20.3~1.21.8 |    ❌     |      ❌       |      ✅      |      ✅       |      ❌       |
| 7.0.61~9.0.75 | 1.21.9~26.2   |    ❌     |      ❌       |      ✅      |      ✅       |      ✅       |
| 10.0.77+      | 26.3+         |    ❌     |      ❌       |      ✅      |      ✅       |      ✅       |

## 版本支持情况
| Minecraft     | Forge | Fabric | NeoForge |
|:--------------|:-----:|:------:|:--------:|
| 1.14.4-       |   ❌   |   ❌    |    ❌     |
| 1.14.4~1.19.4 |   ✅   |   ✅    |    ❌     |
| 1.20.1~26.3   |   ✅   |   ✅    |    ✅     |

