package com.qiushui1012.mod.multiyggdrasil.auth;

import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.qiushui1012.mod.multiyggdrasil.source.BaseYggdrasilSource;

public final class AuthenticatedSource {
    public static final String PROPERTY_NAME = "multiyggdrasil:login_source";

    private AuthenticatedSource() {
    }

    /** Only the endpoint that accepted hasJoined may supply this display-only metadata. */
    public static PropertyMap forSuccessfulLogin(PropertyMap properties, BaseYggdrasilSource source) {
        ImmutableMultimap.Builder<String, Property> result = copyWithoutSource(properties);
        result.put(PROPERTY_NAME, new Property(PROPERTY_NAME, source.getName()));
        return new PropertyMap(result.build());
    }

    /** Profile lookup is not authentication, and upstream properties cannot assert our provenance. */
    public static PropertyMap withoutSource(PropertyMap properties) {
        if (!properties.containsKey(PROPERTY_NAME)) {
            return properties;
        }
        return new PropertyMap(copyWithoutSource(properties).build());
    }

    private static ImmutableMultimap.Builder<String, Property> copyWithoutSource(PropertyMap properties) {
        ImmutableMultimap.Builder<String, Property> result = ImmutableMultimap.builder();
        properties.forEach((name, property) -> {
            if (!PROPERTY_NAME.equals(name)) {
                result.put(name, property);
            }
        });
        return result;
    }
}
