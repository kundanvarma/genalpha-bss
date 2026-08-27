package com.bss.revenue.listen;

import com.bss.revenue.security.TenantContext;
import com.bss.revenue.service.RevenueService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The subledger's ear on DEVICE COMMERCE (bss.device.events): the IFRS 15
 * subsidy machinery. Activation books the contract asset, each instalment
 * unwinds a slice, ETF/swap/withdrawal post their recoveries and reversals,
 * and a financier payout books full recognition plus any residual-value
 * guarantee. At-least-once delivery; every posting's unique source_ref
 * (device-*:<id>) makes replays free.
 */
@Component
@ConditionalOnProperty(name = "bss.events.enabled", havingValue = "true", matchIfMissing = true)
public class DeviceCommerceEventListener {

    private static final Logger log = LoggerFactory.getLogger(DeviceCommerceEventListener.class);
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private final RevenueService revenue;
    private final ObjectMapper objectMapper;

    public DeviceCommerceEventListener(RevenueService revenue, ObjectMapper objectMapper) {
        this.revenue = revenue;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${bss.revenue.device-topic:bss.device.events}", groupId = "revenue-device")
    public void onEvent(String payload) {
        try {
            Map<String, Object> envelope = objectMapper.readValue(payload, JSON_OBJECT);
            String type = String.valueOf(envelope.get("eventType"));
            String tenantId = envelope.get("tenantId") == null ? "genalpha"
                    : String.valueOf(envelope.get("tenantId"));
            switch (type) {
                case "DeviceAgreementActivated" -> {
                    Map<String, Object> agreement = BillingEventListener.resource(envelope, "deviceAgreement");
                    try (TenantContext ignored = TenantContext.actAs(tenantId)) {
                        revenue.postDeviceAgreementActivated(agreement);
                    }
                }
                case "DeviceInstallmentRecordedEvent" -> {
                    Map<String, Object> instalment = BillingEventListener.resource(envelope, "deviceInstallment");
                    try (TenantContext ignored = TenantContext.actAs(tenantId)) {
                        revenue.postDeviceContractAssetUnwind(instalment);
                    }
                }
                case "DeviceAgreementSettled" -> {
                    Map<String, Object> agreement = BillingEventListener.resource(envelope, "deviceAgreement");
                    try (TenantContext ignored = TenantContext.actAs(tenantId)) {
                        revenue.postDeviceEtf(agreement);
                    }
                }
                case "DeviceAgreementSwapped" -> {
                    Map<String, Object> agreement = BillingEventListener.resource(envelope, "deviceAgreement");
                    try (TenantContext ignored = TenantContext.actAs(tenantId)) {
                        revenue.postDeviceSwap(agreement);
                    }
                }
                case "FinancingPayoutReceived" -> {
                    Map<String, Object> agreement = BillingEventListener.resource(envelope, "deviceAgreement");
                    try (TenantContext ignored = TenantContext.actAs(tenantId)) {
                        revenue.postDevicePayout(agreement);
                    }
                }
                case "DeviceAgreementWithdrawn" -> {
                    Map<String, Object> withdrawal = BillingEventListener.resource(envelope, "withdrawalCase");
                    try (TenantContext ignored = TenantContext.actAs(tenantId)) {
                        revenue.postDeviceWithdrawal(withdrawal);
                    }
                }
                default -> {
                    // TradeIn* events carry no ledger money of their own: the
                    // refund books through the payment path, the charge waits
                    // for billing — nothing to post here.
                }
            }
        } catch (Exception e) {
            log.warn("revenue: skipping unprocessable device-commerce event: {}", e.getMessage());
        }
    }
}
