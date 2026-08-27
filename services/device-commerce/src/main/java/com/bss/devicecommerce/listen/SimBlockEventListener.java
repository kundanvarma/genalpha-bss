package com.bss.devicecommerce.listen;

import com.bss.devicecommerce.entity.DeviceAgreement;
import com.bss.devicecommerce.repository.DeviceAgreementRepository;
import com.bss.devicecommerce.security.TenantContext;
import com.bss.devicecommerce.service.TradeInService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Lost/stolen → blacklist seam: a SIM replaced for reason lost/stolen
 * (orchestrator's SimReplacedEvent) flags the IMEI on every active device
 * agreement riding that service — the flag the trade-in quote reads, and
 * the DeviceBlacklistRequested event a real EIR/GSMA adapter would push.
 */
@Component
@ConditionalOnProperty(name = "bss.events.enabled", havingValue = "true", matchIfMissing = true)
public class SimBlockEventListener {

    private static final Logger log = LoggerFactory.getLogger(SimBlockEventListener.class);
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };
    private static final Set<String> BLACKLIST_REASONS = Set.of("lost", "stolen");

    private final DeviceAgreementRepository agreements;
    private final TradeInService tradeIns;
    private final ObjectMapper objectMapper;

    public SimBlockEventListener(DeviceAgreementRepository agreements, TradeInService tradeIns,
            ObjectMapper objectMapper) {
        this.agreements = agreements;
        this.tradeIns = tradeIns;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${bss.device.som-topic:bss.som.events}", groupId = "device-commerce-sim")
    public void onEvent(String payload) {
        try {
            Map<String, Object> envelope = objectMapper.readValue(payload, JSON_OBJECT);
            if (!"SimReplacedEvent".equals(envelope.get("eventType"))) {
                return;
            }
            Map<String, Object> sim = FulfilmentEventListener.resource(envelope, "sim");
            String reason = String.valueOf(sim.get("reason"));
            if (!BLACKLIST_REASONS.contains(reason) || sim.get("serviceId") == null) {
                return;
            }
            String serviceId = String.valueOf(sim.get("serviceId"));
            String tenantId = envelope.get("tenantId") == null ? "genalpha"
                    : String.valueOf(envelope.get("tenantId"));
            String sourceRef = "sim-block:" + envelope.getOrDefault("eventId", serviceId);
            try (TenantContext ignored = TenantContext.actAs(tenantId)) {
                var affected = agreements.findByTenantIdAndSubscriptionRefAndStatus(
                        tenantId, serviceId, DeviceAgreement.ACTIVE);
                for (DeviceAgreement a : affected) {
                    if (a.getImei() != null) {
                        tradeIns.flagImei(a.getImei(), reason, sourceRef);
                    }
                }
                if (affected.isEmpty()) {
                    // no financed device on that service — nothing to flag
                    log.debug("sim block on service {} matched no device agreement", serviceId);
                }
            }
        } catch (Exception e) {
            log.warn("device-commerce: skipping unprocessable SOM event: {}", e.getMessage());
        }
    }
}
