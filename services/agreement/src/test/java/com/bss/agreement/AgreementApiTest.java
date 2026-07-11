package com.bss.agreement;

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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgreementApiTest {

    private static final String BASE = "/tmf-api/agreementManagement/v4";

    @Autowired
    private MockMvc mockMvc;

    private static RequestPostProcessor machine() {
        return jwt().authorities(
                new SimpleGrantedAuthority("agreement:read"),
                new SimpleGrantedAuthority("agreement:write"));
    }

    private static RequestPostProcessor customer(String sub) {
        return jwt().jwt(j -> j.subject(sub)).authorities(
                new SimpleGrantedAuthority("customer"),
                new SimpleGrantedAuthority("agreement:read"));
    }

    private String mint(String party) throws Exception {
        MvcResult result = mockMvc.perform(post(BASE + "/agreement").with(machine())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "GenAlpha One 12-month commitment",
                                 "agreementType": "commercial",
                                 "commitmentMonths": 12,
                                 "engagedParty": [{"id": "%s", "role": "customer"}],
                                 "agreementItem": [{"productOffering": {"id": "po-1", "name": "Bundle"}}]}
                                """.formatted(party)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("inProcess"))
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    @Test
    void lifecycle_activationDerivesTheCommitmentWindow() throws Exception {
        String id = mint("cust-a1");

        mockMvc.perform(patch(BASE + "/agreement/" + id).with(machine())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"active\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.agreementPeriod.startDateTime").isNotEmpty())
                .andExpect(jsonPath("$.agreementPeriod.endDateTime").isNotEmpty())
                .andExpect(jsonPath("$.commitmentMonths").value(12));

        mockMvc.perform(patch(BASE + "/agreement/" + id).with(machine())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"nonsense\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void customersSeeOnlyTheirOwnAgreements() throws Exception {
        String mine = mint("cust-a2");
        mint("cust-other");

        mockMvc.perform(get(BASE + "/agreement").with(customer("cust-a2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(mine));
        mockMvc.perform(get(BASE + "/agreement/" + mine).with(customer("cust-a2")))
                .andExpect(jsonPath("$.engagedParty[0].id").value("cust-a2"));

        // The other customer's agreement reads as absent, and customers cannot write.
        mockMvc.perform(get(BASE + "/agreement/" + mine).with(customer("cust-nosy")))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(BASE + "/agreement").with(customer("cust-a2"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"x\"}"))
                .andExpect(status().isForbidden());
    }
}
