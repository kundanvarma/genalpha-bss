package com.bss.intelligence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What an approval may point at (#358): the domain doors — the TM Forum APIs
 * and the Choices ledger's goodwill — and nothing else; never the AI plane,
 * never a path that climbs out of its door.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApprovalDoorsTest {

    private static final String FILE = "/ai/v1/workforce/approvals";

    @Autowired
    private MockMvc mockMvc;

    private static RequestPostProcessor worker() {
        return jwt().jwt(j -> j.subject("worker-1")).authorities(new SimpleGrantedAuthority("workforce:use"));
    }

    private String body(String path) {
        return """
                {"action": "ontology.grantGoodwillChoice", "method": "POST", "path": "%s",
                 "reason": "goodwill asked for", "body": {"ownerPartyId": "c1", "reason": "outage"}}
                """.formatted(path);
    }

    @Test
    void aTmfDoorAndTheChoicesLedger_mayBeFiled() throws Exception {
        mockMvc.perform(post(FILE).with(worker()).contentType(MediaType.APPLICATION_JSON)
                .content(body("/tmf-api/customerBillManagement/v4/customerBill/b1/creditNote"))).andExpect(status().is2xxSuccessful());
        mockMvc.perform(post(FILE).with(worker()).contentType(MediaType.APPLICATION_JSON)
                .content(body("/benefitAllowance/v1/goodwill"))).andExpect(status().is2xxSuccessful());
    }

    @Test
    void theAiPlane_orAPathThatClimbsOut_isRefused() throws Exception {
        mockMvc.perform(post(FILE).with(worker()).contentType(MediaType.APPLICATION_JSON)
                .content(body("/ai/v1/workforce/approvals/x/approve"))).andExpect(status().isBadRequest());
        mockMvc.perform(post(FILE).with(worker()).contentType(MediaType.APPLICATION_JSON)
                .content(body("/benefitAllowance/v1/../../ai/v1/workforce"))).andExpect(status().isBadRequest());
    }
}
