package com.bss.qualification.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * A TMF679 check. Each item is the caller's own document — it is echoed back
 * with the verdict overlaid — so the items stay open behind the typed body.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PoqCheckRequest(List<Map<String, Object>> productOfferingQualificationItem) {

    public PoqCheckRequest {
        productOfferingQualificationItem = productOfferingQualificationItem == null
                ? List.of() : productOfferingQualificationItem;
    }

    /** The same body reached from the R18 task face, which posts a whole document. */
    @SuppressWarnings("unchecked")
    public static PoqCheckRequest of(Object items) {
        if (!(items instanceof List<?> list)) {
            return new PoqCheckRequest(List.of());
        }
        return new PoqCheckRequest(list.stream()
                .filter(Map.class::isInstance).map(i -> (Map<String, Object>) i).toList());
    }
}
