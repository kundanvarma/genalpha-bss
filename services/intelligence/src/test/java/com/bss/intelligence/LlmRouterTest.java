package com.bss.intelligence;

import com.bss.intelligence.llm.LlmAdapter;
import com.bss.intelligence.security.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** GenAlpha on the default stub; tenant-b brings its own Anthropic stack. */
@SpringBootTest
@ActiveProfiles("test")
class LlmRouterTest {

    @Autowired
    private LlmAdapter llm; // @Primary router

    @Test
    void tenantsWithoutTheirOwnStackUseTheDefault() {
        try (TenantContext ignored = TenantContext.actAs("genalpha")) {
            assertThat(llm.provider()).isEqualTo("stub");
        }
    }

    @Test
    void aConfiguredTenantGetsItsOwnProviderAndModel() {
        try (TenantContext ignored = TenantContext.actAs("tenant-b")) {
            assertThat(llm.provider()).isEqualTo("anthropic");
            assertThat(llm.model()).isEqualTo("claude-test-model");
        }
    }
}
