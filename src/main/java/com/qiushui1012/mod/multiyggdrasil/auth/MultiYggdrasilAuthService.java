package com.qiushui1012.mod.multiyggdrasil.auth;

import com.google.common.collect.ImmutableList;
import com.mojang.authlib.GameProfileRepository;
import com.mojang.authlib.minecraft.SessionService;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.services.ServicesKeySet;
import com.mojang.logging.LogUtils;
import com.qiushui1012.mod.multiyggdrasil.MultiYggdrasil;
import com.qiushui1012.mod.multiyggdrasil.source.BaseYggdrasilSource;
import lombok.Getter;
import org.slf4j.Logger;

import java.net.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MultiYggdrasilAuthService {
    private static final Logger LOGGER = LogUtils.getLogger();

    @Getter
    private final Proxy proxy;
    private final List<BaseYggdrasilSource> sources;
    @Getter
    private final ServicesKeySet servicesKeySet = x -> List.of(new MultiYggdrasilServicesKeyInfo());

    public MultiYggdrasilAuthService(final Proxy proxy) {
        this(proxy, determineSources());
    }

    public MultiYggdrasilAuthService(final Proxy proxy, List<BaseYggdrasilSource> sources) {
        this.proxy = proxy != null ? proxy : Proxy.NO_PROXY;
        this.sources = sources;
        LOGGER.info("Yggdrasil Sources: {}", sources);
    }

    private static List<BaseYggdrasilSource> determineSources() {
        List<BaseYggdrasilSource> envs = MultiYggdrasil.SERVERS_CONFIG.sources();
        if (envs.isEmpty()) {
            return List.of();
        }
        List<BaseYggdrasilSource> result = new ArrayList<>(envs);
        Collections.sort(result);
        return ImmutableList.copyOf(result);
    }

    public GameProfileRepository createProfileRepository() {
        return new MultiYggdrasilGameProfileRepo(this.getProxy(), sources);
    }

    public SessionService createMinecraftSessionService() {
        return new MultiYggdrasilMcSessionService(this, sources);
    }

    public UserApiService createUserApiService(final String accessToken) {
        return UserApiService.OFFLINE;
    }
}
