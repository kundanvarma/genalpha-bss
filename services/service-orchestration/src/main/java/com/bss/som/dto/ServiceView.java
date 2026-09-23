package com.bss.som.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * The TMF638 inventory view of a service — the shape every face builds on:
 * the inventory answers it as is, the TMF640 face overlays the caller's
 * declared document on its tree, the CSR desk and the storefront read it.
 * Every array is non-empty with typed entries (the R18 kit demands it);
 * where the fleet has no relationship the entry SAYS so.
 */
@JsonPropertyOrder({"id", "href", "name", "description", "state", "category", "startDate", "serviceOrderId",
        "suspendReason", "resumeAt", "restriction", "serviceRelationship", "supportingService",
        "serviceSpecification", "relatedParty", "deliveryPath", "place", "supportingResource",
        "serviceCharacteristic", "@type"})
public record ServiceView(
        String id,
        String href,
        String name,
        String description,
        String state,
        String category,
        String startDate,
        String serviceOrderId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String suspendReason,
        @JsonInclude(JsonInclude.Include.NON_NULL) String resumeAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) Restriction restriction,
        List<ServiceRelationship> serviceRelationship,
        List<ServiceRef> supportingService,
        SpecRef serviceSpecification,
        List<PartyRef> relatedParty,
        @JsonInclude(JsonInclude.Include.NON_NULL) String deliveryPath,
        List<PlaceRef> place,
        List<ResourceRef> supportingResource,
        List<Characteristic> serviceCharacteristic,
        @JsonProperty("@type") String type) {

    /** WHY a line is barred, since when, and the profile as stored (open block). */
    @JsonPropertyOrder({"reason", "since", "profile"})
    public record Restriction(String reason, String since, JsonNode profile) {
    }

    @JsonPropertyOrder({"relationshipType", "service"})
    public record ServiceRelationship(String relationshipType, ServiceRef service) {

        public static ServiceRelationship standalone(String id, String href) {
            return new ServiceRelationship("standalone", ServiceRef.at(id, href));
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"id", "href", "name", "role", "@type"})
    public record PlaceRef(String id, String href, String name, String role, @JsonProperty("@type") String type) {

        public static PlaceRef servingSite(String deliveryPath) {
            return new PlaceRef("path:" + deliveryPath,
                    "/tmf-api/geographicSiteManagement/v4/geographicSite/path:" + deliveryPath,
                    deliveryPath, "servingSite", "RelatedPlaceRefOrValue");
        }

        public static PlaceRef serviceArea(String tenantId) {
            return new PlaceRef("sa-" + tenantId,
                    "/tmf-api/geographicSiteManagement/v4/geographicSite/sa-" + tenantId,
                    "service area", "serviceArea", "RelatedPlaceRefOrValue");
        }
    }

    /** What stood the service up or serves it: an issued number, a service order, an activation monitor. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"id", "href", "value", "note", "@referredType"})
    public record ResourceRef(String id, String href, String value, String note,
            @JsonProperty("@referredType") String referredType) {

        public static ResourceRef issued(String assignmentId, String value) {
            return new ResourceRef(assignmentId,
                    "/tmf-api/resourceInventoryManagement/v4/resource/" + assignmentId, value, null, "Resource");
        }

        public static ResourceRef serviceOrder(String serviceOrderId) {
            return new ResourceRef(serviceOrderId,
                    "/tmf-api/serviceOrdering/v4/serviceOrder/" + serviceOrderId, null, null, "ServiceOrder");
        }

        public static ResourceRef monitor(String monitorId, String href) {
            return new ResourceRef(monitorId, href, null,
                    "declared through the activation face — nothing of ours was drawn", "Monitor");
        }
    }
}
