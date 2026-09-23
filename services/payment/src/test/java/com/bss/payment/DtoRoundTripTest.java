package com.bss.payment;

import com.bss.payment.dto.ConfirmRequest;
import com.bss.payment.dto.ExternalPaymentRequest;
import com.bss.payment.dto.MoneyDto;
import com.bss.payment.dto.PaymentDto;
import com.bss.payment.dto.PaymentMethodOption;
import com.bss.payment.dto.PaymentMethodRef;
import com.bss.payment.dto.PaymentSession;
import com.bss.payment.dto.PspConfigRequest;
import com.bss.payment.dto.PspConfigView;
import com.bss.payment.dto.PspTestResult;
import com.bss.payment.dto.RefundReceipt;
import com.bss.payment.dto.RefundRequest;
import com.bss.payment.dto.RelatedPartyRef;
import com.bss.payment.dto.SessionRequest;
import com.bss.payment.dto.VaultMethodRequest;
import com.bss.payment.dto.VaultRecurringRequest;
import com.bss.payment.dto.VaultedRecurringMethod;
import com.bss.payment.dto.WebhookReceipt;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure Jackson: the bytes and the key order of every payment wire record, with
 * no Spring context. A positional record constructor is where a typo lands now,
 * so each factory gets an exact-bytes assertion.
 */
class DtoRoundTripTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private String write(Object o) throws Exception {
        return mapper.writeValueAsString(o);
    }

    @Test
    void paymentMethodKeepsThePostedOrderOfItsOpenKeys() throws Exception {
        String posted = "{\"@type\":\"bankCard\",\"cardNumber\":\"4242424242424242\","
                + "\"expiry\":\"12/28\",\"cvc\":\"123\",\"brand\":\"visa\"}";
        PaymentMethodRef ref = mapper.readValue(posted, PaymentMethodRef.class);
        assertThat(ref.type()).isEqualTo("bankCard");
        assertThat(ref.id()).isNull();
        // a creator-bound any-setter would have handed these back last-first
        assertThat(ref.extensions().keySet()).containsExactly("cardNumber", "expiry", "cvc", "brand");
        assertThat(write(ref)).isEqualTo(posted);
    }

    @Test
    void aMaskedMethodWritesTypeThenLabelAndNothingElse() throws Exception {
        assertThat(write(PaymentMethodRef.masked("bankCard", "bankCard •••• 4242")))
                .isEqualTo("{\"@type\":\"bankCard\",\"label\":\"bankCard •••• 4242\"}");
    }

    @Test
    void aReferenceResolvesToItsVaultId() throws Exception {
        PaymentMethodRef ref = mapper.readValue("{\"id\":\"pm-1\"}", PaymentMethodRef.class);
        assertThat(ref.id()).isEqualTo("pm-1");
        assertThat(ref.extensions()).isEmpty();
        assertThat(write(ref)).isEqualTo("{\"id\":\"pm-1\"}");
    }

    @Test
    void aPaymentWritesItsTmfKeysInDeclarationOrder() throws Exception {
        PaymentDto dto = new PaymentDto();
        dto.setId("p-1");
        dto.setHref("/tmf-api/payment/v4/payment/p-1");
        dto.setStatus("authorized");
        dto.setAmount(new MoneyDto("EUR", new BigDecimal("49.90")));
        dto.setPaymentMethod(PaymentMethodRef.masked("bankCard", "bankCard •••• 4242"));
        dto.setAuthorizationCode("auth-1");
        dto.setRelatedParty(List.of(RelatedPartyRef.payer("cust-a")));
        dto.setPaymentDate(OffsetDateTime.parse("2026-09-23T08:00Z"));
        assertThat(write(dto)).isEqualTo("{\"id\":\"p-1\",\"href\":\"/tmf-api/payment/v4/payment/p-1\","
                + "\"status\":\"authorized\",\"amount\":{\"unit\":\"EUR\",\"value\":49.90},"
                + "\"paymentMethod\":{\"@type\":\"bankCard\",\"label\":\"bankCard •••• 4242\"},"
                + "\"authorizationCode\":\"auth-1\","
                + "\"relatedParty\":[{\"id\":\"cust-a\",\"role\":\"payer\",\"@referredType\":\"Individual\"}],"
                + "\"paymentDate\":\"2026-09-23T08:00:00Z\",\"@type\":\"Payment\"}");
    }

    @Test
    void aRefundReceiptAlwaysCarriesItsReasonAndOmitsAnOwnerlessParty() throws Exception {
        assertThat(write(RefundReceipt.of("p-1", new MoneyDto("EUR", new BigDecimal("10.00")),
                "rf-1", new BigDecimal("10.00"), "refunded", null, "cust-a")))
                .isEqualTo("{\"paymentId\":\"p-1\",\"amount\":{\"unit\":\"EUR\",\"value\":10.00},"
                        + "\"refundRef\":\"rf-1\",\"refundedTotal\":10.00,\"status\":\"refunded\","
                        + "\"reason\":null,\"relatedParty\":[{\"id\":\"cust-a\",\"role\":\"customer\"}],"
                        + "\"@type\":\"Refund\"}");
        assertThat(write(RefundReceipt.of("p-2", new MoneyDto("NOK", new BigDecimal("5.50")),
                "rf-2", new BigDecimal("5.50"), "captured", "goodwill", null)))
                .isEqualTo("{\"paymentId\":\"p-2\",\"amount\":{\"unit\":\"NOK\",\"value\":5.50},"
                        + "\"refundRef\":\"rf-2\",\"refundedTotal\":5.50,\"status\":\"captured\","
                        + "\"reason\":\"goodwill\",\"@type\":\"Refund\"}");
    }

    @Test
    void aSessionNamesTheProviderThatServedAndTheOneItReplaced() throws Exception {
        PaymentSession served = PaymentSession.served("s-1", "https://klarna/redirect", "klarna");
        assertThat(write(served)).isEqualTo("{\"sessionId\":\"s-1\","
                + "\"redirectUrl\":\"https://klarna/redirect\",\"provider\":\"klarna\","
                + "\"@type\":\"PaymentSession\"}");
        assertThat(write(served.failedOverFrom("paypal"))).isEqualTo("{\"sessionId\":\"s-1\","
                + "\"redirectUrl\":\"https://klarna/redirect\",\"provider\":\"klarna\","
                + "\"failedOverFrom\":\"paypal\",\"@type\":\"PaymentSession\"}");
    }

    @Test
    void theSmallReceiptsKeepTheirKeyOrder() throws Exception {
        assertThat(write(VaultedRecurringMethod.of("pm-9", "Klarna", "klarna")))
                .isEqualTo("{\"id\":\"pm-9\",\"label\":\"Klarna\",\"provider\":\"klarna\","
                        + "\"@type\":\"VaultedRecurringMethod\"}");
        assertThat(write(new WebhookReceipt("klarna", "s-1", "p-1", "authorized")))
                .isEqualTo("{\"provider\":\"klarna\",\"sessionId\":\"s-1\","
                        + "\"paymentId\":\"p-1\",\"status\":\"authorized\"}");
        assertThat(write(new PaymentMethodOption("klarna", true)))
                .isEqualTo("{\"method\":\"klarna\",\"redirect\":true}");
        assertThat(write(VaultMethodRequest.bnplToken("klarna", "tok-1", "cust-a")))
                .isEqualTo("{\"@type\":\"bnplToken\",\"details\":{\"brand\":\"klarna\",\"token\":\"tok-1\"},"
                        + "\"relatedParty\":[{\"id\":\"cust-a\",\"role\":\"customer\"}]}");
    }

    @Test
    void aPspRowHidesWhatItDoesNotHaveAndAProbeHidesItsStatus() throws Exception {
        assertThat(write(new PspConfigView("klarna", "Klarna", "http://mock-klarna:8080", "KLARNA_KEY",
                "[\"klarna\"]", true, 10, null, true, "PspConfig")))
                .isEqualTo("{\"provider\":\"klarna\",\"displayName\":\"Klarna\","
                        + "\"baseUrl\":\"http://mock-klarna:8080\",\"secretRef\":\"KLARNA_KEY\","
                        + "\"methods\":\"[\\\"klarna\\\"]\",\"isDefault\":true,\"priority\":10,"
                        + "\"enabled\":true,\"@type\":\"PspConfig\"}");
        assertThat(write(PspTestResult.said("mock", true, "nothing to probe")))
                .isEqualTo("{\"provider\":\"mock\",\"ok\":true,\"note\":\"nothing to probe\"}");
        assertThat(write(PspTestResult.reached("klarna", 200, "probe")))
                .isEqualTo("{\"provider\":\"klarna\",\"ok\":true,\"status\":200,\"note\":\"probe\"}");
        assertThat(PspTestResult.reached("klarna", 503, "probe").ok()).isFalse();
    }

    @Test
    void requestBodiesAcceptWhatEveryChannelPostsAndIgnoreTheRest() throws Exception {
        PspConfigRequest cfg = mapper.readValue("{\"provider\":\"klarna\",\"methods\":[\"klarna\"],"
                + "\"priority\":\"10\",\"isDefault\":true,\"unknown\":1}", PspConfigRequest.class);
        assertThat(cfg.provider()).isEqualTo("klarna");
        assertThat(cfg.methods().toString()).isEqualTo("[\"klarna\"]");
        assertThat(cfg.priority().asText()).isEqualTo("10");
        assertThat(cfg.isDefault()).isTrue();
        assertThat(cfg.enabled()).isNull();

        SessionRequest session = mapper.readValue(
                "{\"method\":\"klarna\",\"amount\":{\"unit\":\"NOK\",\"value\":399.00},"
                        + "\"returnUrl\":\"https://shop/return\"}", SessionRequest.class);
        assertThat(session.amount().getValue()).isEqualByComparingTo("399.00");
        assertThat(session.returnUrl()).isEqualTo("https://shop/return");

        ConfirmRequest confirm = mapper.readValue("{\"provider\":\"klarna\",\"sessionId\":\"s-1\"}",
                ConfirmRequest.class);
        assertThat(confirm.provider()).isEqualTo("klarna");
        VaultRecurringRequest vault = mapper.readValue("{\"provider\":\"Klarna\",\"sessionId\":\"s-1\"}",
                VaultRecurringRequest.class);
        assertThat(vault.provider()).isEqualTo("Klarna");

        ExternalPaymentRequest external = mapper.readValue(
                "{\"amount\":{\"unit\":\"NOK\",\"value\":250.00},\"correlatorId\":\"kid-1\","
                        + "\"description\":\"Giro\",\"reference\":\"OCR-9\",\"ownerPartyId\":\"cust-a\"}",
                ExternalPaymentRequest.class);
        assertThat(external.amount().getValue()).isEqualByComparingTo("250.00");
        assertThat(external.correlatorId()).isEqualTo("kid-1");
        assertThat(external.description()).isEqualTo("Giro");
        assertThat(external.reference()).isEqualTo("OCR-9");
        assertThat(external.ownerPartyId()).isEqualTo("cust-a");

        assertThat(mapper.readValue("{}", RefundRequest.class).amount()).isNull();
        RefundRequest refund = mapper.readValue("{\"amount\":{\"value\":5.50},\"reason\":\"goodwill\"}",
                RefundRequest.class);
        assertThat(refund.amount().getValue()).isEqualByComparingTo("5.50");
        assertThat(refund.reason()).isEqualTo("goodwill");
    }
}
