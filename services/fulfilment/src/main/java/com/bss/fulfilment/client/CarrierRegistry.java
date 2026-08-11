package com.bss.fulfilment.client;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Runtime lookup of carrier adapters by name — per-tenant, not a startup switch. */
@Component
public class CarrierRegistry {

    private final Map<String, CarrierAdapter> byName;

    public CarrierRegistry(List<CarrierAdapter> adapters) {
        this.byName = adapters.stream().collect(Collectors.toMap(CarrierAdapter::name, Function.identity()));
    }

    public CarrierAdapter get(String name) {
        return byName.get(name);
    }
}
