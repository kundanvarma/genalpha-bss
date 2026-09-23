package com.bss.som.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Open access, both sides: the access-seeker orders we placed upstream
 * (MEF Sonata) and what we owe each fibre owner; the orders retailers
 * place on OUR Sonata face as the owner and what each retailer owes us.
 */
public final class WholesaleDtos {

    private WholesaleDtos() {
    }

    /* ---------- seeker side ---------- */

    @JsonPropertyOrder({"id", "productOrderId", "serviceId", "accessOwner", "accessLayer", "bandwidthMbps", "postCode",
            "state", "externalId", "activatedAt", "createdAt", "@type"})
    public record WholesaleAccessOrderView(String id, String productOrderId, String serviceId, String accessOwner,
            String accessLayer, Integer bandwidthMbps, String postCode, String state, String externalId,
            OffsetDateTime activatedAt, OffsetDateTime createdAt, @JsonProperty("@type") String type) {
    }

    @JsonPropertyOrder({"@type", "periodType", "owner", "totalActiveLines", "totalMonthlyOwed", "totalMonthlyMargin",
            "currency"})
    public record WholesaleSettlement(@JsonProperty("@type") String type, String periodType,
            List<OwnerStatement> owner, int totalActiveLines, double totalMonthlyOwed, double totalMonthlyMargin,
            String currency) {
    }

    /** What we owe one owner this month, and the margin the retail price leaves when known. */
    @JsonPropertyOrder({"accessOwner", "accessLayer", "activeLines", "ratePerLine", "monthlyOwed",
            "retailMonthlyPerLine", "marginPerLine", "currency"})
    public record OwnerStatement(String accessOwner, String accessLayer, int activeLines, double ratePerLine,
            double monthlyOwed,
            @JsonInclude(JsonInclude.Include.NON_NULL) Double retailMonthlyPerLine,
            @JsonInclude(JsonInclude.Include.NON_NULL) Double marginPerLine,
            String currency) {
    }

    @JsonPropertyOrder({"id", "activated"})
    public record NotificationReceipt(String id, boolean activated) {
    }

    /** The owner OSS's Sonata notification body: its order id under either name. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SonataNotification(String sonataOrderId, String id) {

        public String orderId() {
            return sonataOrderId != null ? sonataOrderId : id;
        }
    }

    /* ---------- provider side ---------- */

    @JsonPropertyOrder({"id", "state", "@type"})
    public record SonataOrderAck(String id, String state, @JsonProperty("@type") String type) {
    }

    @JsonPropertyOrder({"id", "buyerRef", "retailerPartyId", "accessLayer", "bandwidthMbps", "postCode", "state",
            "activatedAt", "createdAt", "@type"})
    public record ProviderAccessOrderView(String id, String buyerRef, String retailerPartyId, String accessLayer,
            Integer bandwidthMbps, String postCode, String state, OffsetDateTime activatedAt,
            OffsetDateTime createdAt, @JsonProperty("@type") String type) {
    }

    @JsonPropertyOrder({"@type", "periodType", "retailer", "totalMonthlyRevenue", "currency"})
    public record ProviderSettlement(@JsonProperty("@type") String type, String periodType,
            List<RetailerStatement> retailer, double totalMonthlyRevenue, String currency) {
    }

    @JsonPropertyOrder({"retailer", "line", "totalMonthlyCharge", "currency"})
    public record RetailerStatement(String retailer, List<SettlementLine> line, double totalMonthlyCharge,
            String currency) {
    }

    @JsonPropertyOrder({"accessLayer", "activeLines", "ratePerLine", "amount"})
    public record SettlementLine(String accessLayer, int activeLines, double ratePerLine, double amount) {
    }

    /** A retailer's MEF Sonata service order on our face: the facts we read from it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SonataOrderRequest(String externalId, String callbackUrl, String buyerId,
            List<SonataOrderItem> serviceOrderItem) {

        /** The first item's service characteristics, or none. */
        public List<Characteristic> characteristics() {
            return serviceOrderItem == null || serviceOrderItem.isEmpty() || serviceOrderItem.get(0) == null
                    || serviceOrderItem.get(0).service() == null
                    || serviceOrderItem.get(0).service().serviceCharacteristic() == null
                    ? List.of() : serviceOrderItem.get(0).service().serviceCharacteristic();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SonataOrderItem(String action, SonataService service) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SonataService(List<Characteristic> serviceCharacteristic) {
    }
}
