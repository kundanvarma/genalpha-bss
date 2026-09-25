package com.bss.som.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The thin standard faces: TMF653 service tests and their specs, TMF639
 * resources (issued numbers, quarantined numbers, pools), the derived
 * TMF633 service specs, and the TMF640 monitor. What a caller posted and
 * the store keeps verbatim (a test's spec reference, a spec's related
 * spec, a monitor's request and response) stays a {@link JsonNode}.
 */
public final class StandardFaceViews {

    private StandardFaceViews() {
    }

    /* ---------- TMF653 ---------- */

    @JsonPropertyOrder({"id", "href", "name", "relatedService", "testSpecification", "state", "verdict",
            "testMeasure", "createdAt", "@type"})
    public record ServiceTestView(String id, String href, String name, ServiceRef relatedService,
            JsonNode testSpecification, String state, String verdict, JsonNode testMeasure,
            OffsetDateTime createdAt, @JsonProperty("@type") String type) {
    }

    @JsonPropertyOrder({"id", "href", "name", "relatedServiceSpecification", "@type"})
    public record ServiceTestSpecView(String id, String href, String name, JsonNode relatedServiceSpecification,
            @JsonProperty("@type") String type) {
    }

    /** POST serviceTest: which service, under which spec (kept as posted). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ServiceTestRequest(String name, ServiceRef relatedService, JsonNode testSpecification) {

        public String serviceId() {
            return relatedService == null || relatedService.id() == null || relatedService.id().isBlank()
                    ? null : relatedService.id();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ServiceTestSpecRequest(String name, JsonNode relatedServiceSpecification) {
    }

    /** A finding recorded when the referenced service is not in this inventory. */
    @JsonPropertyOrder({"name", "value"})
    public record NamedValue(String name, String value) {
    }

    /* ---------- TMF639 ---------- */

    /** An issued number (assigned) or a quarantined one — the ledger, honestly labeled. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"id", "href", "name", "value", "resourceStatus", "poolId", "relatedService", "relatedParty",
            "resourceSpecification", "@type"})
    public record ResourceView(String id, String href, String name, String value, String resourceStatus,
            String poolId, ServiceRef relatedService, List<PartyRef> relatedParty,
            SpecRef resourceSpecification, @JsonProperty("@type") String type) {

        public static ResourceView assigned(String id, String value, String poolId, String serviceId,
                String ownerPartyId) {
            return new ResourceView(id, "/tmf-api/resourceInventoryManagement/v4/resource/" + id, value, value,
                    "assigned", poolId, serviceId == null ? null : ServiceRef.of(serviceId),
                    PartyRef.customerListOrNull(ownerPartyId), null, "Resource");
        }

        /** The same issued resource, now saying WHAT KIND of thing it is: the TMF634 spec its RFS names. */
        public ResourceView realising(String resourceSpecId, String resourceSpecName) {
            if (resourceSpecId == null) {
                return this;
            }
            return new ResourceView(id, href, name, value, resourceStatus, poolId, relatedService, relatedParty,
                    SpecRef.resourceSpec(resourceSpecId, resourceSpecName), type);
        }

        public static ResourceView quarantined(String number) {
            return new ResourceView("quarantine-" + number,
                    "/tmf-api/resourceInventoryManagement/v4/resource/quarantine-" + number, number, number,
                    "quarantined", null, null, null, null, "Resource");
        }
    }

    /** A pool: the house view {id, name, resourceType, prefix} and, on the TMF639 face, its issued counter. */
    @JsonPropertyOrder({"id", "name", "resourceType", "prefix", "issuedCounter", "note", "@type"})
    public record ResourcePoolView(String id, String name, String resourceType, String prefix,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long issuedCounter,
            @JsonInclude(JsonInclude.Include.NON_NULL) String note,
            @JsonProperty("@type") String type) {

        public static ResourcePoolView of(String id, String name, String resourceType, String prefix) {
            return new ResourcePoolView(id, name, resourceType, prefix, null, null, "ResourcePool");
        }

        public static ResourcePoolView facts(String id, String name, String resourceType, String prefix,
                long issuedCounter) {
            return new ResourcePoolView(id, name, resourceType, prefix, issuedCounter,
                    "this pool is a generator, not a free-list — issued and "
                            + "quarantined are facts; an 'available' count would be an invention", "ResourcePool");
        }
    }

    /* ---------- TMF633 (derived) ---------- */

    @JsonPropertyOrder({"id", "href", "name", "version", "lifecycleStatus", "@type"})
    public record ServiceSpecView(String id, String href, String name, String version, String lifecycleStatus,
            @JsonProperty("@type") String type) {

        public static ServiceSpecView of(String category) {
            return new ServiceSpecView("svcspec-" + category,
                    "/tmf-api/serviceCatalogManagement/v4/serviceSpecification/svcspec-" + category,
                    category + " service", "1.0", "active", "ServiceSpecification");
        }
    }

    /* ---------- TMF640 monitor ---------- */

    @JsonPropertyOrder({"id", "href", "state", "sourceHref", "service", "request", "response", "createdAt", "@type"})
    public record MonitorView(String id, String href, String state, String sourceHref, ServiceRef service,
            JsonNode request, JsonNode response, String createdAt, @JsonProperty("@type") String type) {
    }

    /** What the monitor remembers of the activation call. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"method", "to", "body"})
    public record MonitorRequest(String method, String to, JsonNode body) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"statusCode", "body"})
    public record MonitorResponse(int statusCode, JsonNode body) {
    }
}
