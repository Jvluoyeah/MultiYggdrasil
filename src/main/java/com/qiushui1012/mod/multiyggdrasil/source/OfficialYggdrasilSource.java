package com.qiushui1012.mod.multiyggdrasil.source;


import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class OfficialYggdrasilSource extends BaseYggdrasilSource {
    public static final String DEFAULT_SESSION_HOST = "https://sessionserver.mojang.com";
    public static final String DEFAULT_SERVICES_HOST = "https://api.minecraftservices.com";
    public static final String DEFAULT_PROFILES_HOST = "https://api.mojang.com";

    private final String sessionHost;
    private final String servicesHost;
    private final String profilesHost;
    public OfficialYggdrasilSource(
        String name,
        String sessionHost,
        String servicesHost,
        String profilesHost,
        int ordinal
    ) {
        super(name, ordinal);
        this.sessionHost = sessionHost;
        this.servicesHost = servicesHost;
        this.profilesHost = profilesHost;
    }

    @Override
    public YggdrasilSourceType getType() {
        return YggdrasilSourceType.OFFICIAL;
    }

    @Override
    public String getSessionRoot() {
        return this.sessionHost;
    }

    @Override
    public String getServicesRoot() {
        return this.servicesHost;
    }

    @Override
    public String getProfilesRoot() {
        return this.profilesHost;
    }

    @Override
    public List<String> serialize() {
        List<String> result = new ArrayList<>();
        result.add("type = " + YggdrasilSourceType.OFFICIAL.name());
        if (!Objects.equals(this.sessionHost, DEFAULT_SESSION_HOST)) {
            result.add("sessionHost = " + this.sessionHost);
        }
        if (!Objects.equals(this.servicesHost, DEFAULT_SERVICES_HOST)) {
            result.add("servicesHost = " + this.servicesHost);
        }
        if (!Objects.equals(this.profilesHost, DEFAULT_PROFILES_HOST)) {
            result.add("profilesHost = " + this.profilesHost);
        }
        result.add("ordinal = " + this.ordinal);
        return result;
    }
}
