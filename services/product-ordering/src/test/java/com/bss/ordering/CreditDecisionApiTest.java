package com.bss.ordering;

import com.bss.ordering.client.AgreementClient;
import com.bss.ordering.client.CatalogClient;
import com.bss.ordering.client.PromotionClient;
import com.bss.ordering.repository.CreditDecisionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Credit-decision seam at checkout (Norway rails, P3): the seeded frozen
 * test identity is rejected with the DISTINCT machine code (the storefront
 * keys the prepaid path on it), a declined party rides the existing hold
 * path, everyone else approves and leaves a decision record — never a
 * report.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CreditDecisionApiTest {

    private static final String ORDERS = "/tmf-api/productOrderingManagement/v4/productOrder";
    private static final String CREDIT = "/tmf-api/productOrderingManagement/v4/creditDecision";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CreditDecisionRepository decisions;

    @MockBean
    private CatalogClient catalogClient;

    @MockBean
    private AgreementClient agreementClient;

    @MockBean
    private PromotionClient promotionClient;

    @BeforeEach
    void stubCatalog() {
        given(catalogClient.findOffering(anyString()))
                .willReturn(java.util.Optional.of(new CatalogClient.OfferingRef("po-001", "Stub Offering")));
    }

    private String orderBody(String partyId) {
        return """
                {
                  "productOrderItem": [{"id": "1", "action": "add",
                    "productOffering": {"id": "po-001", "name": "Stub Offering"}}],
                  "relatedParty": [{"id": "%s", "role": "customer"}]
                }
                """.formatted(partyId);
    }

    @Test
    void frozenTestIdentity_getsTheDistinctRejectionCode() throws Exception {
        // 'frozen-test-identity' is seeded by bss.credit.mock.outcomes
        mockMvc.perform(post(ORDERS).with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody("frozen-test-identity")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CREDIT_FROZEN"));

        // the rejection still left its audit trail (REQUIRES_NEW commit)
        assertThat(decisions.findByTenantIdAndPartyIdOrderByDecidedAtDesc(
                "genalpha", "frozen-test-identity"))
                .isNotEmpty()
                .allSatisfy(d -> assertThat(d.getDecision()).isEqualTo("frozen"));
    }

    @Test
    void declinedParty_ridesTheExistingHoldPath() throws Exception {
        mockMvc.perform(post(CREDIT + "/mockOutcome").with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ref\": \"party-declined\", \"decision\": \"decline\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post(ORDERS).with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody("party-declined")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("held"));
    }

    @Test
    void cleanParty_approvesAndTheDecisionIsStored_neverAReport() throws Exception {
        mockMvc.perform(post(ORDERS).with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody("party-clean")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("acknowledged"));

        mockMvc.perform(get(CREDIT).param("relatedPartyId", "party-clean").with(readToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].decision").value("approve"))
                .andExpect(jsonPath("$[0].scoreBand").value("A"))
                // the stored shape is decision + band + booleans, no report
                .andExpect(jsonPath("$[0].report").doesNotExist());
    }

    @Test
    void customerToken_cannotDriveTheMockLever() throws Exception {
        mockMvc.perform(post(CREDIT + "/mockOutcome")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ordering:write"),
                                new SimpleGrantedAuthority("customer")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ref\": \"x\", \"decision\": \"frozen\"}"))
                .andExpect(status().isBadRequest());
    }

    private static RequestPostProcessor writeToken() {
        return jwt().authorities(new SimpleGrantedAuthority("ordering:write"));
    }

    private static RequestPostProcessor readToken() {
        return jwt().authorities(new SimpleGrantedAuthority("ordering:read"));
    }
}
