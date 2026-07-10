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

/**
 * Party scoping: a token with the "customer" role is confined to its own
 * individual — the individual id IS the token subject.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IndividualScopingTest {

    private static final String BASE = "/tmf-api/party/v4/individual";

    @Autowired
    private MockMvc mockMvc;

    private static RequestPostProcessor customer(String sub) {
        return jwt().jwt(j -> j.subject(sub)).authorities(
                new SimpleGrantedAuthority("customer"),
                new SimpleGrantedAuthority("party:read"),
                new SimpleGrantedAuthority("party:write"));
    }

    @Test
    void customerCreate_usesTokenSubjectAsId_andIsIdempotent() throws Exception {
        String body = """
                {"givenName": "Alice", "familyName": "Anders"}
                """;
        mockMvc.perform(post(BASE).with(customer("cust-alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("cust-alice"));

        // Second create returns the existing record, not a duplicate.
        mockMvc.perform(post(BASE).with(customer("cust-alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("cust-alice"));
    }

    @Test
    void customerCannotReadForeignIndividual() throws Exception {
        mockMvc.perform(post(BASE).with(customer("cust-bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"givenName\": \"Bob\", \"familyName\": \"Berg\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get(BASE + "/cust-bob").with(customer("cust-mallory")))
                .andExpect(status().isNotFound());

        mockMvc.perform(get(BASE + "/cust-bob").with(customer("cust-bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.givenName").value("Bob"));
    }

    @Test
    void customerSavesShippingAddress_onOwnIndividual() throws Exception {
        mockMvc.perform(post(BASE).with(customer("cust-ship"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"givenName\": \"Shippy\", \"familyName\": \"McShipface\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch(BASE + "/cust-ship").with(customer("cust-ship"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contactMedium": [{"mediumType": "postalAddress", "characteristic":
                                  {"street1": "Storgatan 1", "city": "Stockholm", "postCode": "11122",
                                   "country": "SE"}}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contactMedium[0].mediumType").value("postalAddress"))
                .andExpect(jsonPath("$.contactMedium[0].characteristic.city").value("Stockholm"));

        // The address round-trips on GET, and stays invisible to others.
        mockMvc.perform(get(BASE + "/cust-ship").with(customer("cust-ship")))
                .andExpect(jsonPath("$.contactMedium[0].characteristic.postCode").value("11122"));
        mockMvc.perform(get(BASE + "/cust-ship").with(customer("cust-nosy")))
                .andExpect(status().isNotFound());
    }

    @Test
    void customerList_returnsOnlyOwnIndividual() throws Exception {
        mockMvc.perform(post(BASE).with(customer("cust-carol"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"givenName\": \"Carol\", \"familyName\": \"Craft\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post(BASE).with(customer("cust-dave"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"givenName\": \"Dave\", \"familyName\": \"Dolt\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get(BASE).with(customer("cust-carol")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("cust-carol"));
    }
}
