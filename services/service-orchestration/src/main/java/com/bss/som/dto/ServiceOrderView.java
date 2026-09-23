package com.bss.som.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * The TMF641 service order: what the production layer did for one product
 * order item, or the order an external system filed. {@code orderItem}
 * carries the caller's own items verbatim (id/state/action defaulted where
 * absent) for an external order, and the single add-item an internal order
 * factually is otherwise.
 */
@JsonPropertyOrder({"id", "href", "state", "category", "orderDate", "productOrderId", "externalId", "priority",
        "description", "completionDate", "orderItem", "@type"})
public record ServiceOrderView(
        String id,
        String href,
        String state,
        String category,
        String orderDate,
        String productOrderId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String externalId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String priority,
        @JsonInclude(JsonInclude.Include.NON_NULL) String description,
        @JsonInclude(JsonInclude.Include.NON_NULL) String completionDate,
        List<JsonNode> orderItem,
        @JsonProperty("@type") String type) {

    /** The derived item of an internal order: one add of the named service. */
    @JsonPropertyOrder({"id", "state", "action", "service"})
    public record DerivedItem(String id, String state, String action, ServiceName service) {

        public static DerivedItem add(String state, String serviceName) {
            return new DerivedItem("1", state, "add", new ServiceName(serviceName));
        }
    }

    public record ServiceName(String name) {
    }
}
