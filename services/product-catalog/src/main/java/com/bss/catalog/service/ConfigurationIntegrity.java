package com.bss.catalog.service;

import com.bss.catalog.dto.EntityRef;
import com.bss.catalog.exception.BadRequestException;
import com.bss.catalog.repository.ProductOfferingPriceRepository;
import com.bss.catalog.repository.ProductSpecificationRepository;
import com.bss.catalog.security.TenantScope;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A configurable product is one offering whose SPECIFICATION declares the choices and whose PRICES condition on
 * them. The two are written separately, so nothing else guarantees they agree — and when they do not, the
 * shop shows no picker and a surcharge can never apply, silently. This check runs when an offering is created
 * or patched, the first moment both sides are known: every condition on a linked price must name a
 * characteristic the specification declares, with a value it allows. Said in words the product owner can act
 * on, never as a stack trace.
 */
@Component
public class ConfigurationIntegrity {

    private final ProductSpecificationRepository specs;
    private final ProductOfferingPriceRepository prices;
    private final TenantScope tenantScope;
    private final ObjectMapper objectMapper;

    public ConfigurationIntegrity(ProductSpecificationRepository specs, ProductOfferingPriceRepository prices,
            TenantScope tenantScope, ObjectMapper objectMapper) {
        this.specs = specs;
        this.prices = prices;
        this.tenantScope = tenantScope;
        this.objectMapper = objectMapper;
    }

    public void check(String offeringName, EntityRef specRef, List<Map<String, Object>> priceRefs) {
        if (specRef == null || priceRefs == null || priceRefs.isEmpty()) {
            return;
        }
        String specId = specRef.id() == null ? "" : specRef.id();
        if (specId.isBlank()) {
            return;
        }
        String tenant = tenantScope.currentTenantId();
        var spec = specs.findByIdAndTenantId(specId, tenant).orElse(null);
        if (spec == null) {
            return; // a dangling reference is another rule's business
        }
        Map<String, Set<String>> declared = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> declaredRaw = new LinkedHashMap<>();
        for (Map<String, Object> c : list(spec.getProductSpecCharacteristicJson())) {
            Set<String> values = new LinkedHashSet<>();
            List<Map<String, Object>> raws = new java.util.ArrayList<>();
            Object raw = c.get("productSpecCharacteristicValue");
            if (raw instanceof List<?> l) {
                for (Object v : l) {
                    if (v instanceof Map<?, ?> m) {
                        @SuppressWarnings("unchecked") Map<String, Object> mv = (Map<String, Object>) m;
                        raws.add(mv);
                        if (m.get("value") != null) {
                            values.add(String.valueOf(m.get("value")));
                        } else if (m.get("valueFrom") != null || m.get("valueTo") != null) {
                            values.add((m.get("valueFrom") == null ? "…" : m.get("valueFrom")) + "–" + (m.get("valueTo") == null ? "…" : m.get("valueTo")));
                        }
                    } else if (v != null) {
                        values.add(String.valueOf(v));
                        raws.add(Map.of("value", String.valueOf(v)));
                    }
                }
            }
            declared.put(String.valueOf(c.get("name")), values);
            declaredRaw.put(String.valueOf(c.get("name")), raws);
        }
        for (Map<String, Object> ref : priceRefs) {
            String priceId = ref == null || ref.get("id") == null ? "" : String.valueOf(ref.get("id"));
            if (priceId.isBlank()) {
                continue;
            }
            var price = prices.findByIdAndTenantId(priceId, tenant).orElse(null);
            if (price == null) {
                continue;
            }
            for (Map<String, Object> cond : list(price.getProdSpecCharValueUseJson())) {
                String name = String.valueOf(cond.get("name"));
                if (!declared.containsKey(name)) {
                    throw new BadRequestException("price \"" + price.getName() + "\" applies only when \"" + name
                            + "\" is chosen, but specification \"" + spec.getName() + "\" declares no such characteristic"
                            + (declared.isEmpty() ? "" : " (it declares: " + String.join(", ", declared.keySet()) + ")")
                            + " — add \"" + name + "\" with its allowed values to the specification first, or the shop can never offer the choice");
                }
                Object raw = cond.get("productSpecCharacteristicValue");
                if (raw instanceof List<?> l) {
                    for (Object v : l) {
                        boolean rangeCondition = v instanceof Map<?, ?> rm && rm.get("value") == null && (rm.get("valueFrom") != null || rm.get("valueTo") != null);
                        if (rangeCondition) {
                            // a price conditioned on a range needs the characteristic to be numeric with a declared range
                            boolean anyRange = declaredRaw.get(name).stream().anyMatch(d -> d.get("valueFrom") != null || d.get("valueTo") != null);
                            if (!anyRange) {
                                throw new BadRequestException("price \"" + price.getName() + "\" applies to a range of \"" + name
                                        + "\", but specification \"" + spec.getName() + "\" declares only fixed values for it — declare a range (valueFrom/valueTo) on the characteristic first");
                            }
                            continue;
                        }
                        String val = v instanceof Map<?, ?> m ? String.valueOf(m.get("value")) : String.valueOf(v);
                        if (!declared.get(name).contains(val) && !ConfiguratorService.valueAllowed(declaredRaw.get(name), val)) {
                            throw new BadRequestException("price \"" + price.getName() + "\" applies only when \"" + name + "\" is \"" + val
                                    + "\", but specification \"" + spec.getName() + "\" allows only: "
                                    + (declared.get(name).isEmpty() ? "no values at all" : String.join(", ", declared.get(name)))
                                    + " — add \"" + val + "\" to the characteristic's allowed values first");
                        }
                    }
                }
            }
        }
    }

    private List<Map<String, Object>> list(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }
}
