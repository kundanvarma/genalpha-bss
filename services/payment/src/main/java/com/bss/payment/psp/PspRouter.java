package com.bss.payment.psp;

import com.bss.payment.service.PspConfigService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Picks the PSP for a charge. A tenant with a provider menu charges through its
 * default configured PSP; a tenant with no menu uses the deployment's global PSP
 * (bss.payment.psp) — existing deployments unchanged. Capture/refund resolve the
 * SAME provider that authorized the payment (recorded on it), so money always
 * settles where it was held. The authorize/capture/refund signatures are
 * untouched; only which adapter answers changes.
 */
@Component
public class PspRouter {

    private final Map<String, PspAdapter> byName;
    private final String defaultName;
    private final PspConfigService configs;

    public PspRouter(List<PspAdapter> adapters, PspConfigService configs,
            @Value("${bss.payment.psp:mock}") String defaultName) {
        this.byName = adapters.stream().collect(Collectors.toMap(PspAdapter::provider, Function.identity()));
        this.defaultName = defaultName;
        this.configs = configs;
    }

    /** The PSP the current tenant charges through (its default, else the global). */
    public PspAdapter forCurrentTenant() {
        return configs.defaultForCurrentTenant()
                .map(c -> byName.get(c.getProvider()))
                .filter(Objects::nonNull)
                .orElseGet(this::defaultAdapter);
    }

    /** The PSP that authorized a payment (for capture/refund), by recorded provider. */
    public PspAdapter byProvider(String provider) {
        PspAdapter a = provider == null ? null : byName.get(provider);
        return a != null ? a : defaultAdapter();
    }

    private PspAdapter defaultAdapter() {
        PspAdapter a = byName.get(defaultName);
        return a != null ? a : byName.values().iterator().next();
    }
}
