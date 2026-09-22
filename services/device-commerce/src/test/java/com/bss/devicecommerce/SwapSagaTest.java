package com.bss.devicecommerce;

import com.bss.devicecommerce.client.PaymentClient;
import com.bss.devicecommerce.dto.DeviceAgreementRequest;
import com.bss.devicecommerce.dto.DeviceAgreementView;
import com.bss.devicecommerce.dto.FinancingSettlement;
import com.bss.devicecommerce.dto.RelatedPartyRef;
import com.bss.devicecommerce.dto.ResidualRequest;
import com.bss.devicecommerce.dto.SwapReceipt;
import com.bss.devicecommerce.dto.SwapRequest;
import com.bss.devicecommerce.dto.TradeInQuoteRequest;
import com.bss.devicecommerce.dto.TradeInValuationView;
import com.bss.devicecommerce.dto.UpgradeRule;
import com.bss.devicecommerce.service.DeviceAgreementService;
import com.bss.devicecommerce.service.TradeInService;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private PaymentClient payments;

    private DeviceAgreementView agreement(String model, String party, BigDecimal residualValue, String paymentRef) {
        return agreements.create(new DeviceAgreementRequest(new BigDecimal("720"), 24, model,
                new BigDecimal("720"), List.of(RelatedPartyRef.customer(party)), null, null, null, null, null,
                null, null, null, null, new UpgradeRule(new BigDecimal("50"), null), residualValue, null, null,
                null, paymentRef));
    }

    private String acceptedValuation(String imei) {
        tradeIns.upsertResidual(new ResidualRequest("phone-saga", 0, new BigDecimal("250"), null));
        TradeInValuationView v = tradeIns.quote(new TradeInQuoteRequest(imei, "phone-saga", null, null, null,
                null, null));
        tradeIns.accept(v.id());
        return v.id();
    }

    private void payToEligibility(String agreementId) {
        for (int month = 0; month < 12; month++) {
            agreements.recordInstallment(agreementId);
        }
    }

    @Test
    void operatorBookSwap_writesOffRemainderAgainstGradedValue() {
        DeviceAgreementView a = agreement("OPERATOR_BOOK", "saga-ob", null, null);
        payToEligibility(a.id());
        String valuation = acceptedValuation("353000000000001");

        SwapReceipt swapped = agreements.swap(a.id(), new SwapRequest(valuation));
        assertThat(swapped.agreement().status()).isEqualTo("swapped");
        FinancingSettlement settlement = swapped.settlement();
        // 360 remaining − 250 trade-in = 110 written off by the program
        assertThat(settlement.remainingPrincipal()).isEqualByComparingTo("360.00");
        assertThat(settlement.writeOff()).isEqualByComparingTo("110.00");

        // replay is free — the saga is idempotent, and carries no settlement facts
        SwapReceipt replay = agreements.swap(a.id(), new SwapRequest(valuation));
        assertThat(replay.agreement().status()).isEqualTo("swapped");
        assertThat(replay.settlement()).isNull();
    }

    @Test
    void mockBankSwap_settlesTheEarlySettlementQuote() {
        DeviceAgreementView a = agreement("THIRD_PARTY_LOAN", "saga-bank", new BigDecimal("100"), null);
        assertThat(a.payoutReceivedAt()).isNotNull();   // the bank paid the operator out
        payToEligibility(a.id());
        String valuation = acceptedValuation("353000000000002");

        SwapReceipt swapped = agreements.swap(a.id(), new SwapRequest(valuation));
        FinancingSettlement settlement = swapped.settlement();
        // bank quote: 360 remaining + 49 flat fee; the 250 trade-in leaves 159
        assertThat(settlement.settlementAmount()).isEqualByComparingTo("409.00");
        assertThat(settlement.shortfall()).isEqualByComparingTo("159.00");
    }

    @Test
    void bnplSwap_delegatesSettlementToTheProvider() {
        Mockito.when(payments.payment("pay-bnpl-1")).thenReturn(objectMapper.valueToTree(Map.of(
                "id", "pay-bnpl-1", "status", "captured", "pspProvider", "klarna")));
        DeviceAgreementView a = agreement("BNPL", "saga-bnpl", null, "pay-bnpl-1");
        assertThat(a.titleHolder()).isEqualTo("provider");
        payToEligibility(a.id());
        String valuation = acceptedValuation("353000000000003");

        SwapReceipt swapped = agreements.swap(a.id(), new SwapRequest(valuation));
        FinancingSettlement settlement = swapped.settlement();
        assertThat(settlement.settlementDelegated()).isTrue();
        assertThat(settlement.providerSettlementStatus()).isEqualTo("settled");
        // never an operator write-off on this model
        assertThat(settlement.writeOff()).isNull();
    }
}
