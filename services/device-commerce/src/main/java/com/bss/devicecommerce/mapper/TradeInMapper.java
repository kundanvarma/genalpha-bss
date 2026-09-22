package com.bss.devicecommerce.mapper;

import com.bss.devicecommerce.dto.DeviceFlagView;
import com.bss.devicecommerce.dto.GradingEventView;
import com.bss.devicecommerce.dto.RelatedPartyRef;
import com.bss.devicecommerce.dto.TradeInResidualView;
import com.bss.devicecommerce.dto.TradeInValuationView;
import com.bss.devicecommerce.entity.DeviceFlag;
import com.bss.devicecommerce.entity.GradingEvent;
import com.bss.devicecommerce.entity.TradeInResidual;
import com.bss.devicecommerce.entity.TradeInValuation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Entities → the trade-in wire views. The condition answers are the
 * customer's document, stored as JSON text and echoed as a tree.
 */
@Component
public class TradeInMapper {

    private final ObjectMapper objectMapper;

    public TradeInMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public TradeInValuationView view(TradeInValuation v) {
        return new TradeInValuationView(
                v.getId(),
                v.getHref(),
                v.getImei(),
                v.getDeviceRef(),
                v.getStatus(),
                v.getEstimatedValue(),
                v.getFinalValue(),
                v.getDelta(),
                v.getCurrency(),
                v.getOfferExpiry().toString(),
                v.getChannel(),
                v.getConditionJson() == null ? null : readJson(v.getConditionJson()),
                v.getPaymentRef(),
                v.getRefundRef(),
                v.getAgreementRef(),
                v.getPartyId() == null ? null : List.of(RelatedPartyRef.customer(v.getPartyId())),
                TradeInValuationView.TYPE,
                null,
                null);
    }

    public GradingEventView view(GradingEvent g) {
        return new GradingEventView(g.getId(), g.getPartnerRef(), g.getFinalGrade(), g.getFinalValue(),
                g.getDelta(), g.getNote(), g.getCreatedAt().toString());
    }

    public TradeInResidualView view(TradeInResidual r) {
        return new TradeInResidualView(r.getId(), r.getDeviceRef(), r.getAgeMonths(), r.getBaseValue(),
                r.getCurrency(), TradeInResidualView.TYPE);
    }

    public DeviceFlagView view(DeviceFlag f) {
        return new DeviceFlagView(f.getId(), f.getImei(), f.getFlag(), f.getReason(), f.getSourceRef(),
                f.getCreatedAt().toString(), DeviceFlagView.TYPE);
    }

    /** The answers as the customer sent them; a missing or non-object document is an empty one. */
    public JsonNode answers(JsonNode conditionAnswers) {
        return conditionAnswers != null && conditionAnswers.isObject() ? conditionAnswers
                : objectMapper.createObjectNode();
    }

    public String writeJson(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private JsonNode readJson(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            return node == null || node.isMissingNode() ? objectMapper.createObjectNode() : node;
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }
}
