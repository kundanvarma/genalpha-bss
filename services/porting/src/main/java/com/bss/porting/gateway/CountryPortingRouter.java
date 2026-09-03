package com.bss.porting.gateway;

import com.bss.porting.security.TenantRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Porting is the OPERATOR's business, and national. The clearinghouse is chosen
 * in this order: (1) the tenant's own choice (tenants.yml `porting-gateway` —
 * a Norwegian operator says nrdb, a Guyanese one portingxs), (2) the number's
 * country (bss.porting.gateways.&lt;ISO&gt;), (3) bss.porting.gateway as the
 * deployment fallback (the dev mock). Never a single deployment-wide switch
 * again: two operators in one fleet must be able to port through two bodies.
 */
@Component
@Primary
public class CountryPortingRouter implements PortingGateway {

    private final Map<String, PortingGateway> byName;
    private final Map<String, String> byCountry;
    private final String fallback;
    private final TenantRegistry tenants;

    public CountryPortingRouter(List<PortingGateway> adapters, TenantRegistry tenants,
            @Value("#{${bss.porting.gateways:{:}}}") Map<String, String> byCountry,
            @Value("${bss.porting.gateway:mock}") String fallback) {
        this.tenants = tenants;
        this.byName = adapters.stream()
                .filter(a -> !(a instanceof CountryPortingRouter))
                .collect(Collectors.toMap(PortingGateway::name, Function.identity()));
        this.byCountry = byCountry == null ? Map.of() : byCountry.entrySet().stream()
                .filter(e -> e.getValue() != null && !e.getValue().isBlank())
                .collect(Collectors.toMap(e -> e.getKey().toUpperCase(Locale.ROOT), e -> e.getValue().trim()));
        this.fallback = fallback == null || fallback.isBlank() ? "mock" : fallback;
    }

    public PortingGateway forCountry(String country) {
        return forTenantAndCountry(null, country);
    }

    /** The tenant's own clearinghouse when it names one; else the number's country; else the fallback. */
    public PortingGateway forTenantAndCountry(String tenantId, String country) {
        String key = null;
        TenantRegistry.TenantEntry te = tenantId == null ? null : tenants.byId(tenantId);
        if (te != null && te.getPortingGateway() != null && !te.getPortingGateway().isBlank()) {
            key = te.getPortingGateway().trim();
        }
        if (key == null) {
            key = byCountry.getOrDefault(country == null ? "" : country.toUpperCase(Locale.ROOT), fallback);
        }
        PortingGateway g = byName.get(key);
        if (g == null) {
            g = byName.get(fallback);
        }
        return g == null ? byName.get("mock") : g;
    }

    @Override
    public Decision validate(PortingRequest request) {
        return forTenantAndCountry(request.tenantId(), request.country()).validate(request);
    }

    @Override
    public boolean confirmCutover(PortingRequest request) {
        return forTenantAndCountry(request.tenantId(), request.country()).confirmCutover(request);
    }

    @Override
    public String name() {
        return "router";
    }

    @Override
    public String nameFor(String tenantId, String country) {
        return forTenantAndCountry(tenantId, country).name();
    }
}
