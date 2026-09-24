package com.bss.qualification.service;

import com.bss.qualification.dto.PoqCheckRequest;
import com.bss.qualification.dto.PoqCheckResult;
import com.bss.qualification.entity.ServiceableArea;
import com.bss.qualification.repository.ServiceableAreaRepository;
import com.bss.qualification.security.TenantScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static com.bss.qualification.api.Wire.idOf;

/**
 * TMF679 check: each qualification item names an offering and a place. An
 * offering with no serviceable areas qualifies anywhere; a gated one
 * qualifies only where the place's postcode matches a configured prefix.
 */
@Service
public class QualificationService {

    public static final String QUALIFIED = "qualified";
    public static final String UNQUALIFIED = "unqualified";

    private final ServiceableAreaRepository areas;
    private final TenantScope tenantScope;

    public QualificationService(ServiceableAreaRepository areas, TenantScope tenantScope) {
        this.areas = areas;
        this.tenantScope = tenantScope;
    }

    @Transactional(readOnly = true)
    public PoqCheckResult check(PoqCheckRequest request) {
        List<Map<String, Object>> resultItems = new ArrayList<>();
        boolean allQualified = true;
        for (Map<String, Object> item : request.productOfferingQualificationItem()) {
            Map<String, Object> result = qualifyItem(item);
            resultItems.add(result);
            allQualified &= QUALIFIED.equals(result.get("qualificationItemResult"));
        }
        return PoqCheckResult.of(UUID.randomUUID().toString(),
                allQualified ? QUALIFIED : UNQUALIFIED, resultItems);
    }

    /* The verdict is OVERLAID on the caller's own item, key for key — including
     * the HashMap iteration order the caller's keys land in. That echo is the
     * contract every channel reads, so the item stays an open map here while
     * the envelope around it is a record. */

    private Map<String, Object> qualifyItem(Map<String, Object> item) {
        Map<String, Object> result = new HashMap<>(item);
        String offeringId = idOf(item.get("productOffering"));
        String offeringName = item.get("productOffering") instanceof Map<?, ?> ref && ref.get("name") != null
                ? String.valueOf(ref.get("name"))
                : offeringId;
        if (offeringId == null) {
            result.put("qualificationItemResult", UNQUALIFIED);
            result.put("eligibilityUnavailabilityReason", List.of(
                    Map.of("code", "missingOffering", "label", "qualification item without productOffering.id")));
            return result;
        }
        List<ServiceableArea> gates = areas.findByTenantIdAndProductOfferingId(
                tenantScope.currentTenantId(), offeringId);
        result.put("serviceabilityGated", !gates.isEmpty());
        if (gates.isEmpty()) {
            result.put("qualificationItemResult", QUALIFIED);
            return result;
        }
        String postCode = item.get("place") instanceof Map<?, ?> place && place.get("postCode") != null
                ? String.valueOf(place.get("postCode")).replaceAll("\\s", "")
                : "";
        boolean serviceable = gates.stream()
                .anyMatch(a -> postCode.startsWith(a.getPostcodePrefix().replaceAll("\\s", "")));
        if (serviceable) {
            result.put("qualificationItemResult", QUALIFIED);
        } else {
            result.put("qualificationItemResult", UNQUALIFIED);
            result.put("eligibilityUnavailabilityReason", List.of(Map.of(
                    "code", "notServiceable",
                    "label", offeringName + " is not available at postcode "
                            + (postCode.isEmpty() ? "(none given)" : postCode))));
        }
        return result;
    }
}
