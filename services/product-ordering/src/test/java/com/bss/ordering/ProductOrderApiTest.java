package com.bss.ordering;

import com.bss.ordering.client.AgreementClient;
import com.bss.ordering.client.PromotionClient;
import com.bss.ordering.client.CatalogClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductOrderApiTest {

    private static final String BASE = "/tmf-api/productOrderingManagement/v4/productOrder";

    @Autowired
    private MockMvc mockMvc;

    // Orders in these tests reference offering "po-001"; orchestration validates
    // references against the catalog, which is not running here.
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

    @Test
    void createProductOrder_returns201WithGeneratedId() throws Exception {
        String body = """
                {
                  "productOrderItem": [{"id": "1", "action": "add"}],
                      "state": "acknowledged",
                  "description": "New mobile subscription order",
                  "productOfferingId": "po-001"
                }
                """;

        mockMvc.perform(post(BASE).with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(not(emptyOrNullString())))
                .andExpect(jsonPath("$.href").value(not(emptyOrNullString())))
                .andExpect(jsonPath("$.state").value("acknowledged"))
                .andExpect(jsonPath("$.productOfferingId").value("po-001"))
                .andExpect(jsonPath("$.['@type']").value("ProductOrder"));
    }

    @Test
    void listProductOrders_returns200() throws Exception {
        mockMvc.perform(get(BASE).with(readToken()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void verifiedIdentityOffering_blockedWithoutStepUp_allowedWith() throws Exception {
        given(catalogClient.findOffering("po-verified"))
                .willReturn(java.util.Optional.of(new CatalogClient.OfferingRef(
                        "po-verified", "Postpaid (ID verified)", null, true)));
        String order = """
                {"productOrderItem": [{"action": "add", "productOffering": {"id": "po-verified"}}],
                 "relatedParty": [{"id": "vi-party", "role": "customer"}]}
                """;

        // No verified identity in the token -> 403 with the step-up signal.
        mockMvc.perform(post(BASE).with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON).content(order))
                .andExpect(status().isForbidden())
                .andExpect(header().string("X-Step-Up", "verified-identity"))
                .andExpect(jsonPath("$.code").value("VERIFIED_IDENTITY_REQUIRED"));

        // A stepped-up token (verified_identity claim) -> the order goes through.
        mockMvc.perform(post(BASE)
                        .with(jwt().jwt(j -> j.claim("verified_identity", true))
                                .authorities(new SimpleGrantedAuthority("ordering:write")))
                        .contentType(MediaType.APPLICATION_JSON).content(order))
                .andExpect(status().isCreated());
    }

    private static RequestPostProcessor readToken() {
        return jwt().authorities(new SimpleGrantedAuthority("ordering:read"));
    }

    private static RequestPostProcessor writeToken() {
        return jwt().authorities(new SimpleGrantedAuthority("ordering:write"));
    }
}
