package com.bss.payment.psp;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Runtime lookup of redirect/BNPL providers by name — per-tenant, not a startup switch. */
@Component
public class RedirectPspRegistry {

    private final Map<String, RedirectPspAdapter> byName;

    public RedirectPspRegistry(List<RedirectPspAdapter> adapters) {
        this.byName = adapters.stream().collect(Collectors.toMap(RedirectPspAdapter::name, Function.identity()));
    }

    public RedirectPspAdapter get(String name) {
        return byName.get(name);
    }
}
