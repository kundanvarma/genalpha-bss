package com.bss.som.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * A reference to a service: the standalone self-reference in a relationship
 * ({id, href}), the supporting service that says it supports itself
 * ({id, href, name, note}), a test's related service, a monitor's service —
 * and, since catalog-to-provisioning step 2, a resource-facing service the
 * orchestrator REALISED for a service (seam, vendor, externalRef, declared
 * ride beside the standard fields; null means absent, so every older view
 * serialises byte-identically).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"id", "href", "name", "note", "seam", "vendor", "externalRef", "declared", "@referredType"})
@JsonIgnoreProperties(ignoreUnknown = true)
public record ServiceRef(String id, String href, String name, String note,
        String seam, String vendor, String externalRef, Boolean declared,
        @JsonProperty("@referredType") String referredType) {

    public ServiceRef(String id, String href, String name, String note) {
        this(id, href, name, note, null, null, null, null, null);
    }

    public static ServiceRef of(String id) {
        return new ServiceRef(id, null, null, null);
    }

    public static ServiceRef at(String id, String href) {
        return new ServiceRef(id, href, null, null);
    }

    /** {id, href} pointing at the TMF638 inventory row. */
    public static ServiceRef inventory(String id) {
        return new ServiceRef(id, "/tmf-api/serviceInventory/v4/service/" + id, null, null);
    }

    /**
     * A resource-facing service the orchestrator realised for this service:
     * the RFS spec when the CFS declared one for the seam (declared = true),
     * else the bare seam (declared = false — an undeclared realisation, the
     * raw material of the catalog-versus-code gate).
     */
    public static ServiceRef realised(String rfsId, String rfsName, String seam, String vendor, String externalRef) {
        boolean declared = rfsId != null;
        return new ServiceRef(rfsId,
                declared ? "/tmf-api/serviceCatalogManagement/v4/serviceSpecification/" + rfsId : null,
                rfsName != null ? rfsName : seam, null, seam, vendor, externalRef, declared,
                declared ? "ServiceSpecification" : null);
    }
}
