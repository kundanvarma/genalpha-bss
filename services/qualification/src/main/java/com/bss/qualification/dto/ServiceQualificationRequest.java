package com.bss.qualification.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * A TMF645 request, for all three faces (check, query, access options). The
 * blocks stay open because the standard lets the caller put the place in four
 * different spots and post it as an object or a list; the record's job here is
 * that a body cannot carry a field the service never declared.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ServiceQualificationRequest(
        Object searchCriteria,
        Object service,
        Object place,
        Object serviceQualificationItem,
        Object technology) {

    /** The same body reached from the R18 v3 task face, which posts a whole document. */
    public static ServiceQualificationRequest of(Map<String, Object> document) {
        return new ServiceQualificationRequest(document.get("searchCriteria"),
                document.get("service"), document.get("place"),
                document.get("serviceQualificationItem"), document.get("technology"));
    }
}
