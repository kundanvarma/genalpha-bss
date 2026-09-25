package com.bss.som.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** A specification reference: service spec, test spec, related service spec. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"id", "href", "name", "version"})
@JsonIgnoreProperties(ignoreUnknown = true)
public record SpecRef(String id, String href, String name, String version) {

    /** The derived TMF633 spec every inventory row of a category points at. */
    public static SpecRef serviceSpec(String category) {
        return new SpecRef("svcspec-" + category,
                "/tmf-api/serviceCatalogManagement/v4/serviceSpecification/svcspec-" + category,
                category + " service", "1.0");
    }

    /** The real TMF633 CFS (served by the product catalog) an inventory row realises. */
    public static SpecRef cfs(String id, String name) {
        return new SpecRef(id, "/tmf-api/serviceCatalogManagement/v4/serviceSpecification/" + id, name, null);
    }

    /** The built-in TMF653 spec: the CSR diagnose triage. */
    public static SpecRef diagnose() {
        return new SpecRef("diagnose",
                "/tmf-api/serviceTestManagement/v4/serviceTestSpecification/diagnose",
                "diagnose triage", null);
    }
}
