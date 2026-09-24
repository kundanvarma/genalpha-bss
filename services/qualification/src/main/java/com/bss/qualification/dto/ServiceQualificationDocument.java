package com.bss.qualification.dto;

import com.fasterxml.jackson.annotation.JsonAutoDetect;

import java.util.List;
import java.util.Map;

/** TMF645 R18 (v3) ServiceQualification, as posted: the typed envelope, the TMF sub-documents open. */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY,
        getterVisibility = JsonAutoDetect.Visibility.NONE, isGetterVisibility = JsonAutoDetect.Visibility.NONE)
public class ServiceQualificationDocument extends OpenTaskDocument {

    private String externalId;
    private String description;
    private String expectedQualificationDate;
    private String estimatedResponseDate;
    private Boolean instantSyncQualification;
    private Boolean provideAlternative;
    private Boolean provideUnavailabilityReason;
    private List<Map<String, Object>> relatedParty;
    private List<Map<String, Object>> serviceQualificationItem;
    private Map<String, Object> searchCriteria;
    private Map<String, Object> service;
    private Object place;
    private Object technology;

    public String externalId() { return externalId; }
    public List<Map<String, Object>> serviceQualificationItem() { return serviceQualificationItem; }

    @Override
    protected void declared(Map<String, Object> into) {
        put(into, "externalId", externalId);
        put(into, "description", description);
        put(into, "expectedQualificationDate", expectedQualificationDate);
        put(into, "estimatedResponseDate", estimatedResponseDate);
        put(into, "instantSyncQualification", instantSyncQualification);
        put(into, "provideAlternative", provideAlternative);
        put(into, "provideUnavailabilityReason", provideUnavailabilityReason);
        put(into, "relatedParty", relatedParty);
        put(into, "searchCriteria", searchCriteria);
        put(into, "service", service);
        put(into, "place", place);
        put(into, "technology", technology);
        put(into, "serviceQualificationItem", serviceQualificationItem);
    }
}
