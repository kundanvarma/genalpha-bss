package com.bss.devicecommerce;

import com.bss.devicecommerce.client.PaymentClient;
import com.bss.devicecommerce.service.DeviceAgreementService;
import com.bss.devicecommerce.service.TradeInService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The swap saga, once per financing model: operator-book writes off the
 * remainder against the graded value, the mock bank settles its quote
 * (remaining + fee), BNPL delegates settlement to the provider. */
@SpringBootTest
@ActiveProfiles("test")
class SwapSagaTest {

    @Autowired
    private DeviceAgreementService agreements;

    @Autowired
    private TradeInService tradeIns;

    @MockitoBean
    private PaymentClient payments;

    private Map<String, Object> agreement(String model, String party, Map<String, Object> extra) {
        java.util.Map<String, Object> dto = new java.util.LinkedHashMap<>();
        dto.put("principal", 720);
        dto.put("termMonths", 24);
        dto.put("financingModel", model);
        dto.put("totalCostOfOwnership", 720);
        dto.put("upgradeRule", Map.of("paidSharePct", 50));
        dto.put("relatedParty", List.of(Map.of("id", party, "role", "customer")));
        dto.putAll(extra);
        return agreements.create(dto);
    }

    private String acceptedValuation(String imei) {
        tradeIns.upsertResidual(Map.of("deviceRef", "phone-saga", "ageMonths", 0, "baseValue", 250));
        Map<String, Object> v = tradeIns.quote(Map.of("imei", imei, "deviceRef", "phone-saga"));
        tradeIns.accept(String.valueOf(v.get("id")));
        return String.valueOf(v.get("id"));
    }

    private void payToEligibility(String agreementId) {
        for (int month = 0; month < 12; month++) {
            agreements.recordInstallment(agreementId);
        }
    }

    @Test
    void operatorBookSwap_writesOffRemainderAgainstGradedValue() {
        Map<String, Object> a = agreement("OPERATOR_BOOK", "saga-ob", Map.of());
        String id = String.valueOf(a.get("id"));
        payToEligibility(id);
        String valuation = acceptedValuation("353000000000001");

        Map<String, Object> swapped = agreements.swap(id, Map.of("tradeInValuationId", valuation));
        assertThat(swapped.get("status")).isEqualTo("swapped");
        @SuppressWarnings("unchecked")
        Map<String, Object> settlement = (Map<String, Object>) swapped.get("settlement");
        // 360 remaining − 250 trade-in = 110 written off by the program
        assertThat((BigDecimal) settlement.get("remainingPrincipal"))
                .isEqualByComparingTo("360.00");
        assertThat((BigDecimal) settlement.get("writeOff")).isEqualByComparingTo("110.00");

        // replay is free — the saga is idempotent
        Map<String, Object> replay = agreements.swap(id, Map.of("tradeInValuationId", valuation));
        assertThat(replay.get("status")).isEqualTo("swapped");
    }

    @Test
    void mockBankSwap_settlesTheEarlySettlementQuote() {
        Map<String, Object> a = agreement("THIRD_PARTY_LOAN", "saga-bank", Map.of("residualValue", 100));
        String id = String.valueOf(a.get("id"));
        assertThat(a.get("payoutReceivedAt")).isNotNull();   // the bank paid the operator out
        payToEligibility(id);
        String valuation = acceptedValuation("353000000000002");

        Map<String, Object> swapped = agreements.swap(id, Map.of("tradeInValuationId", valuation));
        @SuppressWarnings("unchecked")
        Map<String, Object> settlement = (Map<String, Object>) swapped.get("settlement");
        // bank quote: 360 remaining + 49 flat fee; the 250 trade-in leaves 159
        assertThat((BigDecimal) settlement.get("settlementAmount")).isEqualByComparingTo("409.00");
        assertThat((BigDecimal) settlement.get("shortfall")).isEqualByComparingTo("159.00");
    }

    @Test
    void bnplSwap_delegatesSettlementToTheProvider() {
        Mockito.when(payments.payment("pay-bnpl-1")).thenReturn(Map.of(
                "id", "pay-bnpl-1", "status", "captured", "pspProvider", "klarna"));
        Map<String, Object> a = agreement("BNPL", "saga-bnpl", Map.of("paymentRef", "pay-bnpl-1"));
        String id = String.valueOf(a.get("id"));
        assertThat(a.get("titleHolder")).isEqualTo("provider");
        payToEligibility(id);
        String valuation = acceptedValuation("353000000000003");

        Map<String, Object> swapped = agreements.swap(id, Map.of("tradeInValuationId", valuation));
        @SuppressWarnings("unchecked")
        Map<String, Object> settlement = (Map<String, Object>) swapped.get("settlement");
        assertThat(settlement.get("settlementDelegated")).isEqualTo(true);
        assertThat(settlement.get("providerSettlementStatus")).isEqualTo("settled");
        // never an operator write-off on this model
        assertThat(settlement).doesNotContainKey("writeOff");
    }
}
