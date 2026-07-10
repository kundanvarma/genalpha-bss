package com.bss.communication;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pool-model tenancy: messages belong to the tenant of the verified token
 * issuer that sent them. Cross-tenant access must behave as if the message
 * does not exist (404, never 403), and inboxes must never leak foreign rows
 * — even for the same party id under a different tenant's issuer.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CommunicationTenancyTest {

    private static final String BASE = "/tmf-api/communicationManagement/v4/communicationMessage";
    private static final String ISSUER_A = "https://idp.tenant-a.test/realms/bss";
    private static final String ISSUER_B = "https://idp.tenant-b.test/realms/bss";

    @Autowired
    private MockMvc mockMvc;

    private static RequestPostProcessor staffOf(String issuer) {
        return jwt().jwt(j -> j.issuer(issuer)).authorities(
                new SimpleGrantedAuthority("communication:read"),
                new SimpleGrantedAuthority("communication:write"));
    }

    private static RequestPostProcessor customerOf(String issuer, String sub) {
        return jwt().jwt(j -> j.issuer(issuer).subject(sub)).authorities(
                new SimpleGrantedAuthority("customer"),
                new SimpleGrantedAuthority("communication:read"),
                new SimpleGrantedAuthority("communication:write"));
    }

    private String sendAs(String issuer, String receiver, String subject) throws Exception {
        MvcResult result = mockMvc.perform(post(BASE).with(staffOf(issuer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subject": "%s",
                                 "content": "hello",
                                 "relatedParty": [{"id": "%s", "role": "customer"}]}
                                """.formatted(subject, receiver)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    @Test
    void tenantsCannotSeeEachOthersMessages() throws Exception {
        String idA = sendAs(ISSUER_A, "cust-ten-1", "Tenant-A campaign");

        // The receiver in the owning tenant reads it; the same party id under
        // the other tenant's issuer gets 404, not 403.
        mockMvc.perform(get(BASE + "/" + idA).with(customerOf(ISSUER_A, "cust-ten-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("Tenant-A campaign"));
        mockMvc.perform(get(BASE + "/" + idA).with(customerOf(ISSUER_B, "cust-ten-1")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(BASE + "/" + idA).with(staffOf(ISSUER_B)))
                .andExpect(status().isNotFound());

        // Inboxes and staff lists never leak foreign rows.
        mockMvc.perform(get(BASE + "?limit=100").with(customerOf(ISSUER_B, "cust-ten-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        MvcResult listB = mockMvc.perform(get(BASE + "?limit=100").with(staffOf(ISSUER_B)))
                .andExpect(status().isOk()).andReturn();
        if (listB.getResponse().getContentAsString().contains(idA)) {
            throw new AssertionError("tenant B list leaked tenant A's message");
        }
    }

    @Test
    void writesAreConfinedToTheCallersTenant() throws Exception {
        String idA = sendAs(ISSUER_A, "cust-ten-2", "Tenant-A patch target");

        // Marking a foreign tenant's message read reads as 404.
        mockMvc.perform(patch(BASE + "/" + idA).with(customerOf(ISSUER_B, "cust-ten-2"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"read\"}"))
                .andExpect(status().isNotFound());

        // Still intact and unread for its owner.
        mockMvc.perform(get(BASE + "/" + idA).with(customerOf(ISSUER_A, "cust-ten-2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("sent"));
    }
}
