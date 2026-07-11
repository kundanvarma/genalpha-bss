package com.bss.recommendation;

import com.bss.recommendation.client.CommerceClients;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RecommendationApiTest {

    private static final String BASE = "/tmf-api/recommendationManagement/v4";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CommerceClients.CatalogClient catalog;

    @MockBean
    private CommerceClients.InventoryClient inventory;

    @Test
    void recommendsWhatTheCustomerLacks_bundlesFirst_ownedExcluded() throws Exception {
        given(catalog.activeOfferings()).willReturn(List.of(
                Map.of("id", "po-phone", "name", "Nice Phone", "isBundle", false),
                Map.of("id", "po-bundle", "name", "Big Bundle", "isBundle", true),
                Map.of("id", "po-owned", "name", "Already Mine", "isBundle", false),
                Map.of("id", "po-hidden", "name", "Internal", "isSellable", false)));
        given(inventory.productsOf("cust-r1")).willReturn(List.of(
                Map.of("id", "prod-1", "productOffering", Map.of("id", "po-owned"))));

        mockMvc.perform(get(BASE + "/recommendation")
                        .with(jwt().jwt(j -> j.subject("cust-r1")).authorities(
                                new SimpleGrantedAuthority("customer"),
                                new SimpleGrantedAuthority("recommendation:read"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].recommendationItem.length()").value(2))
                .andExpect(jsonPath("$[0].recommendationItem[0].offering.name").value("Big Bundle"))
                .andExpect(jsonPath("$[0].recommendationItem[1].offering.name").value("Nice Phone"));
    }

    @Test
    void requiresIdentity() throws Exception {
        mockMvc.perform(get(BASE + "/recommendation"))
                .andExpect(status().isUnauthorized());
    }
}
