package com.bss.devicecommerce;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Device commerce end to end on H2: residual-table quote, blacklist zero,
 * agreement + schedule + eligibility + swap, grading delta, withdrawal. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DeviceCommerceApiTest {

    private static final String BASE = "/tmf-api/deviceCommerce/v1";

    @Autowired
    private MockMvc mockMvc;

    private static RequestPostProcessor staff() {
        return jwt().authorities(
                new SimpleGrantedAuthority("device:read"),
                new SimpleGrantedAuthority("device:write"));
    }

    private String extractId(String json) {
        return json.replaceFirst("^\\{\"id\":\"([^\"]+)\".*$", "$1");
    }

    @Test
    void residualTableFeedsTheQuote_andDefectsTakeTheirPublishedHaircut() throws Exception {
        mockMvc.perform(post(BASE + "/tradeInResidual").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceRef": "phone-alpha-128", "ageMonths": 12, "baseValue": 300}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseValue").value(300.0));

        // clean device at 12 months: full base value
        mockMvc.perform(post(BASE + "/tradeInValuation").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"imei": "350000000000001", "deviceRef": "phone-alpha-128",
                                 "conditionAnswers": {"ageMonths": 12}}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("quoted"))
                .andExpect(jsonPath("$.estimatedValue").value(300.0));

        // cracked screen: −40 %
        mockMvc.perform(post(BASE + "/tradeInValuation").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"imei": "350000000000002", "deviceRef": "phone-alpha-128",
                                 "conditionAnswers": {"ageMonths": 12, "screenCracked": true}}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.estimatedValue").value(180.0));
    }

    @Test
    void blacklistedImeiQuotesZero() throws Exception {
        mockMvc.perform(post(BASE + "/tradeInResidual").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceRef": "phone-beta-256", "ageMonths": 0, "baseValue": 500}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post(BASE + "/deviceFlag").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"imei": "350999999999999", "reason": "stolen"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flag").value("blacklisted"));

        mockMvc.perform(post(BASE + "/tradeInValuation").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"imei": "350999999999999", "deviceRef": "phone-beta-256"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.estimatedValue").value(0.0))
                .andExpect(jsonPath("$.note").value(containsString("blacklisted")));
    }

    @Test
    void totalCostOfOwnershipIsRequired_theOmbudsmanLesson() throws Exception {
        mockMvc.perform(post(BASE + "/deviceAgreement").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"principal": 720, "termMonths": 24, "financingModel": "OPERATOR_BOOK",
                                 "relatedParty": [{"id": "party-tco", "role": "customer"}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("totalCostOfOwnership")));
    }

    @Test
    void operatorBookLifecycle_scheduleEligibilitySwapWithWriteOffMath() throws Exception {
        String agreementId = extractId(mockMvc.perform(post(BASE + "/deviceAgreement").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"principal": 720, "termMonths": 24, "financingModel": "OPERATOR_BOOK",
                                 "totalCostOfOwnership": 720, "subsidyAmount": 240,
                                 "imei": "351000000000001", "deviceRef": "phone-alpha-128",
                                 "upgradeRule": {"paidSharePct": 50},
                                 "relatedParty": [{"id": "party-ob", "role": "customer"}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.monthlyAmount").value(30.0))
                .andExpect(jsonPath("$.titleHolder").value("operator"))
                .andReturn().getResponse().getContentAsString());

        // not eligible before half the principal is paid
        mockMvc.perform(get(BASE + "/deviceAgreement/" + agreementId + "/upgradeEligibility").with(staff()))
                .andExpect(jsonPath("$.eligible").value(false));
        mockMvc.perform(post(BASE + "/deviceAgreement/" + agreementId + "/swap").with(staff())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());

        for (int month = 0; month < 12; month++) {
            mockMvc.perform(post(BASE + "/deviceAgreement/" + agreementId + "/recordInstallment")
                    .with(staff())).andExpect(status().isOk());
        }
        mockMvc.perform(get(BASE + "/deviceAgreement/" + agreementId + "/upgradeEligibility").with(staff()))
                .andExpect(jsonPath("$.eligible").value(true));

        // the swap needs an ACCEPTED trade-in behind it
        mockMvc.perform(post(BASE + "/tradeInResidual").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceRef": "phone-alpha-128", "ageMonths": 12, "baseValue": 300}
                                """))
                .andExpect(status().isOk());
        String valuationId = extractId(mockMvc.perform(post(BASE + "/tradeInValuation").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"imei": "351000000000001", "deviceRef": "phone-alpha-128",
                                 "conditionAnswers": {"ageMonths": 12},
                                 "relatedParty": [{"id": "party-ob", "role": "customer"}]}
                                """))
                .andReturn().getResponse().getContentAsString());
        mockMvc.perform(post(BASE + "/tradeInValuation/" + valuationId + "/accept").with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("accepted"));

        // remaining 360 written off against the 300 trade-in: 60 is the program's cost
        mockMvc.perform(post(BASE + "/deviceAgreement/" + agreementId + "/swap").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tradeInValuationId\": \"" + valuationId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("swapped"))
                .andExpect(jsonPath("$.settlement.remainingPrincipal").value(360.0))
                .andExpect(jsonPath("$.settlement.writeOff").value(60.0));

        // replaying the swap is free
        mockMvc.perform(post(BASE + "/deviceAgreement/" + agreementId + "/swap").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tradeInValuationId\": \"" + valuationId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("swapped"));
    }

    @Test
    void mockBankAgreement_receivesPayoutAndQuotesEarlySettlementWithFee() throws Exception {
        String agreementId = extractId(mockMvc.perform(post(BASE + "/deviceAgreement").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"principal": 600, "termMonths": 12, "financingModel": "THIRD_PARTY_LOAN",
                                 "totalCostOfOwnership": 600, "residualValue": 150,
                                 "relatedParty": [{"id": "party-bank", "role": "customer"}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.titleHolder").value("financier"))
                .andExpect(jsonPath("$.externalAgreementNo").value(containsString("MB-")))
                .andExpect(jsonPath("$.payoutReceivedAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString());

        // 600 remaining + the bank's flat 49 fee
        mockMvc.perform(get(BASE + "/deviceAgreement/" + agreementId + "/earlySettlementQuote")
                        .with(staff()))
                .andExpect(jsonPath("$.amount").value(649.0))
                .andExpect(jsonPath("$.fee").value(49.0));
    }

    @Test
    void bnplWithoutAResolvablePayment_isRefused() throws Exception {
        // the payment component is unreachable in this profile: origination must refuse
        mockMvc.perform(post(BASE + "/deviceAgreement").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"principal": 500, "termMonths": 12, "financingModel": "BNPL",
                                 "totalCostOfOwnership": 500, "paymentRef": "no-such-payment",
                                 "relatedParty": [{"id": "party-bnpl", "role": "customer"}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("does not resolve")));
    }

    @Test
    void gradingDelta_negativeWaitsForTheCustomer_positiveSettles() throws Exception {
        mockMvc.perform(post(BASE + "/tradeInResidual").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceRef": "phone-gamma-64", "ageMonths": 0, "baseValue": 200}
                                """))
                .andExpect(status().isOk());
        String valuationId = extractId(mockMvc.perform(post(BASE + "/tradeInValuation").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"imei": "352000000000001", "deviceRef": "phone-gamma-64"}
                                """))
                .andReturn().getResponse().getContentAsString());
        mockMvc.perform(post(BASE + "/tradeInValuation/" + valuationId + "/accept").with(staff()))
                .andExpect(status().isOk());
        mockMvc.perform(post(BASE + "/tradeInValuation/" + valuationId + "/inTransit").with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("in-transit"));

        // partner grades DOWN: revalued, the customer decides
        mockMvc.perform(post(BASE + "/tradeInValuation/" + valuationId + "/grading").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"partnerRef": "grading-partner-1", "finalGrade": "C",
                                 "finalValue": 150, "note": "deep scratches"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("revalued"))
                .andExpect(jsonPath("$.delta").value(-50.0));
        mockMvc.perform(post(BASE + "/tradeInValuation/" + valuationId + "/acceptRevaluation")
                        .with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("settled"));

        // a second device grades UP: settles immediately (nobody rejects more money)
        String upId = extractId(mockMvc.perform(post(BASE + "/tradeInValuation").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"imei": "352000000000002", "deviceRef": "phone-gamma-64"}
                                """))
                .andReturn().getResponse().getContentAsString());
        mockMvc.perform(post(BASE + "/tradeInValuation/" + upId + "/accept").with(staff()))
                .andExpect(status().isOk());
        mockMvc.perform(post(BASE + "/tradeInValuation/" + upId + "/grading").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"partnerRef": "grading-partner-1", "finalGrade": "A", "finalValue": 220}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("settled"))
                .andExpect(jsonPath("$.delta").value(20.0));
    }

    @Test
    void withdrawalInsideTheWindow_refundsPrincipalPlusShipping() throws Exception {
        String agreementId = extractId(mockMvc.perform(post(BASE + "/deviceAgreement").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"principal": 800, "termMonths": 24, "financingModel": "OPERATOR_BOOK",
                                 "totalCostOfOwnership": 800, "shippingCost": 9.90,
                                 "relatedParty": [{"id": "party-wd", "role": "customer"}]}
                                """))
                .andReturn().getResponse().getContentAsString());

        // a deduction without a documented return grade is refused
        mockMvc.perform(post(BASE + "/withdrawalCase").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agreementId\": \"" + agreementId + "\", \"deduction\": 50}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post(BASE + "/withdrawalCase").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agreementId\": \"" + agreementId + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("refunded"))
                .andExpect(jsonPath("$.refundAmount").value(809.90));

        mockMvc.perform(get(BASE + "/deviceAgreement/" + agreementId).with(staff()))
                .andExpect(jsonPath("$.status").value("withdrawn"));
    }

    @Test
    void deviceCommerceRequiresAuthentication() throws Exception {
        mockMvc.perform(post(BASE + "/deviceAgreement")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE + "/deviceAgreement"))
                .andExpect(status().isUnauthorized());
    }
}
