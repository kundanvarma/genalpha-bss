package com.bss.intelligence;

import com.bss.intelligence.audit.AiAuditRepository;
import com.bss.intelligence.security.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CopilotApiTest {

    private static final String BASE = "/ai/v1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AiAuditRepository audits;

    private static RequestPostProcessor agent() {
        return jwt().authorities(new SimpleGrantedAuthority("ai:use"));
    }

    @Test
    void summarizesTheCustomer360() throws Exception {
        mockMvc.perform(post(BASE + "/customerSummary").with(agent())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerName": "Erik", "context": {
                                  "orders": [{"state": "completed"}],
                                  "tickets": [{"name": "Slow broadband", "status": "inProgress"}],
                                  "usage": [{"meter": "data", "used": 42, "included": 50}]}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").isNotEmpty())
                .andExpect(jsonPath("$.nextActions", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.provider").value("stub"));
    }

    @Test
    void draftsATicketReplyAndRedactsTheContext() throws Exception {
        mockMvc.perform(post(BASE + "/ticketReply").with(agent())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ticket": {"name": "No signal", "status": "acknowledged",
                                            "note": "customer at +46 70 111 22 33, mail erik@example.com"}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").isNotEmpty());

        try (TenantContext ignored = TenantContext.actAsSystem()) {
            assertThat(audits.findAll())
                    .anyMatch(a -> "ticket-reply".equals(a.getUseCase())
                            && !a.getPrompt().contains("erik@example.com")
                            && !a.getPrompt().contains("111 22 33")
                            && a.getPrompt().contains("[email]"));
        }
    }

    @Test
    void copilotIsGatedByTheAiRole() throws Exception {
        mockMvc.perform(post(BASE + "/customerSummary")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"x\": 1}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsEmptyPayloads() throws Exception {
        mockMvc.perform(post(BASE + "/customerSummary").with(agent())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(BASE + "/ticketReply").with(agent())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }
}
