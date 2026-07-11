package com.bss.party;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PartyRoleApiTest {

    private static final String PARTY = "/tmf-api/party/v4";
    private static final String ROLES = "/tmf-api/partyRoleManagement/v4";

    @Autowired
    private MockMvc mockMvc;

    private static RequestPostProcessor customer(String sub) {
        return jwt().jwt(j -> j.subject(sub)).authorities(
                new SimpleGrantedAuthority("customer"),
                new SimpleGrantedAuthority("party:read"),
                new SimpleGrantedAuthority("party:write"));
    }

    private static RequestPostProcessor staff() {
        return jwt().authorities(
                new SimpleGrantedAuthority("party:read"),
                new SimpleGrantedAuthority("party:write"));
    }

    @Test
    void selfRegistrationMintsTheCustomerRole_visibleToItsOwnerOnly() throws Exception {
        mockMvc.perform(post(PARTY + "/individual").with(customer("cust-pr1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"givenName\": \"Rolle\", \"familyName\": \"Kund\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get(ROLES + "/partyRole").with(customer("cust-pr1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("customer"))
                .andExpect(jsonPath("$[0].engagedParty.id").value("cust-pr1"));

        mockMvc.perform(get(ROLES + "/partyRole").with(customer("cust-nosy")))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void backOfficeGrantsAreIdempotent_customerRoleUndeletable() throws Exception {
        mockMvc.perform(post(ROLES + "/partyRole").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"partner\", \"engagedParty\": {\"id\": \"org-77\"}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("partner"));
        mockMvc.perform(post(ROLES + "/partyRole").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"partner\", \"engagedParty\": {\"id\": \"org-77\"}}"))
                .andExpect(status().isCreated());
        mockMvc.perform(get(ROLES + "/partyRole").param("engagedPartyId", "org-77").with(staff()))
                .andExpect(jsonPath("$.length()").value(1));

        // The auto-minted customer role cannot be deleted through the API.
        mockMvc.perform(post(PARTY + "/individual").with(customer("cust-pr2"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"givenName\": \"Del\", \"familyName\": \"Test\"}"))
                .andExpect(status().isCreated());
        String roleId = com.jayway.jsonpath.JsonPath.read(
                mockMvc.perform(get(ROLES + "/partyRole").param("engagedPartyId", "cust-pr2").with(staff()))
                        .andReturn().getResponse().getContentAsString(), "$[0].id");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete(ROLES + "/partyRole/" + roleId).with(staff()))
                .andExpect(status().isBadRequest());
    }
}
