package com.bss.qualification.dto;

import com.fasterxml.jackson.annotation.JsonAutoDetect;

import java.util.List;
import java.util.Map;

/** TMF679 R18 ProductOfferingQualification, as posted: the typed envelope, the TMF sub-documents open. */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY,
        getterVisibility = JsonAutoDetect.Visibility.NONE, isGetterVisibility = JsonAutoDetect.Visibility.NONE)
public class PoqDocument extends OpenTaskDocument {

    private String externalId;
    private String description;
    private String expectedQualificationDate;
    private String expectedPoqCompletionDate;
    private Boolean instantSyncQualification;
    private Boolean provideAlternative;
    private Boolean provideOnlyAvailable;
    private Boolean provideUnavailabilityReason;
    private List<Map<String, Object>> relatedParty;
    private Map<String, Object> channel;
    private List<Map<String, Object>> productOfferingQualificationItem;

    public List<Map<String, Object>> productOfferingQualificationItem() { return productOfferingQualificationItem; }

    @Override
    protected void declared(Map<String, Object> into) {
        put(into, "externalId", externalId);
        put(into, "description", description);
        put(into, "expectedQualificationDate", expectedQualificationDate);
        put(into, "expectedPoqCompletionDate", expectedPoqCompletionDate);
        put(into, "instantSyncQualification", instantSyncQualification);
        put(into, "provideAlternative", provideAlternative);
        put(into, "provideOnlyAvailable", provideOnlyAvailable);
        put(into, "provideUnavailabilityReason", provideUnavailabilityReason);
        put(into, "relatedParty", relatedParty);
        put(into, "channel", channel);
        put(into, "productOfferingQualificationItem", productOfferingQualificationItem);
    }
}
