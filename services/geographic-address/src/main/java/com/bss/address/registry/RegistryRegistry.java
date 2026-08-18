package com.bss.address.registry;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Every {@link RegistryAdapter} bean, keyed by provider — discovery IS the
 * registration, same as CarrierRegistry. */
@Component
public class RegistryRegistry {

    private final Map<String, RegistryAdapter> byProvider;

    public RegistryRegistry(List<RegistryAdapter> adapters) {
        this.byProvider = adapters.stream()
                .collect(Collectors.toMap(RegistryAdapter::provider, Function.identity()));
    }

    public RegistryAdapter get(String provider) {
        return byProvider.get(provider);
    }
}
