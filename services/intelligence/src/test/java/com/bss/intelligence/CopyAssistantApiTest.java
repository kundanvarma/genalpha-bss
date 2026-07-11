package com.bss.intelligence;

import com.bss.intelligence.audit.AiAudit;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CopyAssistantApiTest {

    private static final String BASE = "/ai/v1";
    private static final String ISSUER_A = "https://idp.tenant-a.test/realms/bss";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AiAuditRepository audits;

    private static RequestPostProcessor staff() {
        return jwt().authorities(new SimpleGrantedAuthority("ai:use"));
    }

    private static RequestPostProcessor staffOf(String issuer) {
        return jwt().jwt(j -> j.issuer(issuer))
                .authorities(new SimpleGrantedAuthority("ai:use"));
    }

    @Test
    void draftsCopyAndGuaranteesTheCodePlaceholder() throws Exception {
        mockMvc.perform(post(BASE + "/campaignCopy").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"brief": "welcome new customers warmly",
                                 "brandName": "MyGenAlpha",
                                 "triggerEventType": "ProductOrderCreateEvent",
                                 "promotionCode": "WELCOME10"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").isNotEmpty())
                .andExpect(jsonPath("$.content").value(containsString("{code}")))
                .andExpect(jsonPath("$.provider").value("stub"));
    }

    @Test
    void redactsPiiBeforeItLeavesTheBox() throws Exception {
        mockMvc.perform(post(BASE + "/campaignCopy").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"brief": "reach anna.svensson@example.com and +46 70 123 45 67 about upgrades"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value(not(containsString("anna.svensson"))));

        try (TenantContext ignored = TenantContext.actAsSystem()) {
            List<AiAudit> rows = audits.findAll();
            assertThat(rows).isNotEmpty();
            AiAudit latest = rows.get(rows.size() - 1);
            assertThat(latest.getPrompt()).doesNotContain("anna.svensson@example.com");
            assertThat(latest.getPrompt()).doesNotContain("123 45 67");
            assertThat(latest.getPrompt()).contains("[email]").contains("[phone]");
        }
    }

    @Test
    void everyCallLandsInTheTenantsAuditLedger() throws Exception {
        mockMvc.perform(post(BASE + "/campaignCopy").with(staffOf(ISSUER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"brief\": \"audited draft\"}"))
                .andExpect(status().isOk());

        try (TenantContext ignored = TenantContext.actAsSystem()) {
            assertThat(audits.findByTenantIdOrderByCreatedAtDesc("tenant-a"))
                    .anyMatch(a -> "campaign-copy".equals(a.getUseCase())
                            && "stub".equals(a.getProvider())
                            && a.getPrompt().contains("audited draft"));
        }
    }

    @Test
    void requiresTheAiRole() throws Exception {
        mockMvc.perform(post(BASE + "/campaignCopy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"brief\": \"x\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(BASE + "/campaignCopy")
                        .with(jwt().authorities(new SimpleGrantedAuthority("campaign:write")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"brief\": \"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsAnEmptyBrief() throws Exception {
        mockMvc.perform(post(BASE + "/campaignCopy").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
