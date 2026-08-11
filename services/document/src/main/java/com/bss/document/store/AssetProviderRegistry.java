package com.bss.document.store;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Runtime lookup of asset providers by name — NOT a startup @ConditionalOnProperty
 * switch, because provider choice is per-tenant (one deployment can serve
 * tenant A from Sanity and tenant B from the hosted DAM). Every AssetProvider
 * bean self-registers under its {@link AssetProvider#name()}.
 */
@Component
public class AssetProviderRegistry {

    private final Map<String, AssetProvider> byName;

    public AssetProviderRegistry(List<AssetProvider> providers) {
        this.byName = providers.stream().collect(
                Collectors.toMap(AssetProvider::name, Function.identity()));
    }

    /** The provider for a name, or null if none is configured/on the classpath. */
    public AssetProvider get(String name) {
        return byName.get(name);
    }
}
