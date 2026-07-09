package com.bss.party;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PartyAccountApiTest {

    private static final String INDIVIDUAL_BASE = "/tmf-api/party/v4/individual";
    private static final String BILLING_ACCOUNT_BASE = "/tmf-api/accountManagement/v4/billingAccount";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createIndividual_returns201WithGeneratedId() throws Exception {
        String body = """
                {
                  "givenName": "John",
                  "familyName": "Doe"
                }
                """;

        mockMvc.perform(post(INDIVIDUAL_BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(not(emptyOrNullString())))
                .andExpect(jsonPath("$.href").value(not(emptyOrNullString())))
                .andExpect(jsonPath("$.givenName").value("John"))
                .andExpect(jsonPath("$.familyName").value("Doe"))
                .andExpect(jsonPath("$.['@type']").value("Individual"));
    }

    @Test
    void createBillingAccount_returns201WithGeneratedId() throws Exception {
        String body = """
                {
                  "name": "John Doe Billing",
                  "state": "active"
                }
                """;

        mockMvc.perform(post(BILLING_ACCOUNT_BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(not(emptyOrNullString())))
                .andExpect(jsonPath("$.href").value(not(emptyOrNullString())))
                .andExpect(jsonPath("$.name").value("John Doe Billing"))
                .andExpect(jsonPath("$.state").value("active"))
                .andExpect(jsonPath("$.['@type']").value("BillingAccount"));
    }

    @Test
    void listIndividuals_returns200() throws Exception {
        mockMvc.perform(get(INDIVIDUAL_BASE).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }
}
