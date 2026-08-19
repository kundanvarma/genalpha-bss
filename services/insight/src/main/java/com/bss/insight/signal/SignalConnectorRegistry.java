package com.bss.insight.signal;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Every {@link SignalConnectorAdapter} bean, keyed by kind — discovery IS
 * the registration, same as CarrierRegistry and RegistryRegistry. */
@Component
public class SignalConnectorRegistry {

    private final Map<String, SignalConnectorAdapter> byKind;

    public SignalConnectorRegistry(List<SignalConnectorAdapter> adapters) {
        this.byKind = adapters.stream()
                .collect(Collectors.toMap(SignalConnectorAdapter::kind, Function.identity()));
    }

    public SignalConnectorAdapter get(String kind) {
        return byKind.get(kind);
    }
}
