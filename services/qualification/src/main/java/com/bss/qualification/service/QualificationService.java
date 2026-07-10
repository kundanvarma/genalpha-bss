package com.bss.qualification.service;

import com.bss.qualification.entity.ServiceableArea;
import com.bss.qualification.repository.ServiceableAreaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    public QualificationService(ServiceableAreaRepository areas) {
        this.areas = areas;
    }

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Map<String, Object> check(Map<String, Object> request) {
        List<Map<String, Object>> items = request.get("productOfferingQualificationItem") instanceof List<?> l
                ? (List<Map<String, Object>>) l
                : List.of();
        List<Map<String, Object>> resultItems = new ArrayList<>();
        boolean allQualified = true;
        for (Map<String, Object> item : items) {
            Map<String, Object> result = qualifyItem(item);
            resultItems.add(result);
            allQualified &= QUALIFIED.equals(result.get("qualificationItemResult"));
        }
        Map<String, Object> response = new HashMap<>();
        response.put("id", UUID.randomUUID().toString());
        response.put("@type", "CheckProductOfferingQualification");
        response.put("state", "done");
        response.put("qualificationResult", allQualified ? QUALIFIED : UNQUALIFIED);
        response.put("productOfferingQualificationItem", resultItems);
        return response;
    }

    private Map<String, Object> qualifyItem(Map<String, Object> item) {
        Map<String, Object> result = new HashMap<>(item);
        String offeringId = item.get("productOffering") instanceof Map<?, ?> ref && ref.get("id") != null
                ? String.valueOf(ref.get("id"))
                : null;
        String offeringName = item.get("productOffering") instanceof Map<?, ?> ref && ref.get("name") != null
                ? String.valueOf(ref.get("name"))
                : offeringId;
        if (offeringId == null) {
            result.put("qualificationItemResult", UNQUALIFIED);
            result.put("eligibilityUnavailabilityReason", List.of(
                    Map.of("code", "missingOffering", "label", "qualification item without productOffering.id")));
            return result;
        }
        List<ServiceableArea> gates = areas.findByProductOfferingId(offeringId);
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
