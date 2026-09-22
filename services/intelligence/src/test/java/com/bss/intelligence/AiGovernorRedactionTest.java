package com.bss.intelligence;

import com.bss.intelligence.audit.AiAudit;
import com.bss.intelligence.audit.AiAuditRepository;
import com.bss.intelligence.audit.AiBudgetRepository;
import com.bss.intelligence.audit.AiContractRepository;
import com.bss.intelligence.llm.AiGovernor;
import com.bss.intelligence.llm.LlmAdapter;
import com.bss.intelligence.security.TenantRegistry;
import com.bss.intelligence.security.TenantScope;
import com.bss.intelligence.service.Redactor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What reaches the provider. A recording adapter stands in for the model;
 * the assertions are on ITS arguments, not on the ledger copy.
 */
class AiGovernorRedactionTest {

    private static final String SYSTEM = "You draft a reply for the care desk.";
    private static final String USER = """
            Ticket note: customer anna@example.com, phone +47 912 34 567, IBAN NO93 8601 1117 947,
            SIM ICCID 8947000000000000001, fnr 01019912345. Address: Storgata 12, 0155 Oslo""";

    /** The model under test: remembers the prompt and answers with placeholders. */
    static final class RecordingAdapter implements LlmAdapter {
        String system;
        String user;

        @Override
        public String complete(String system, String user) {
            this.system = system;
            this.user = user;
            return "Reply to <email#1> at <phone#1> about the SIM <iccid#1>.";
        }

        @Override
        public String provider() {
            return "fake";
        }

        @Override
        public String model() {
            return "fake-1";
        }
    }

    private final RecordingAdapter provider = new RecordingAdapter();
    private final AiAuditRepository audits = mock(AiAuditRepository.class);

    private AiGovernor governor(boolean rawExposure) {
        AiBudgetRepository budgets = mock(AiBudgetRepository.class);
        when(budgets.findByTenantId(anyString())).thenReturn(Optional.empty());
        AiContractRepository contracts = mock(AiContractRepository.class);
        when(contracts.findByTenantIdAndUseCase(anyString(), anyString())).thenReturn(Optional.empty());
        TenantScope scope = mock(TenantScope.class);
        when(scope.currentTenantId()).thenReturn("t1");
        TenantRegistry registry = new TenantRegistry();
        TenantRegistry.TenantEntry entry = new TenantRegistry.TenantEntry();
        entry.setId("t1");
        entry.setAiRawExposure(rawExposure);
        registry.setRegistry(List.of(entry));
        return new AiGovernor(provider, audits, budgets, contracts, scope, new Redactor(),
                mock(PlatformTransactionManager.class), 2000L, registry, new MockEnvironment());
    }

    private AiAudit ledgerRow() {
        ArgumentCaptor<AiAudit> row = ArgumentCaptor.forClass(AiAudit.class);
        verify(audits).save(row.capture());
        return row.getValue();
    }

    @Test
    void withRawExposureOffTheProviderNeverSeesAPerson() {
        String answer = governor(false).complete("ticket-reply", LlmAdapter.Tier.FAST, SYSTEM, USER);

        assertThat(provider.user)
                .doesNotContain("anna@example.com", "912 34 567", "8601 1117 947",
                        "8947000000000000001", "01019912345", "Storgata")
                .contains("<email#1>", "<phone#1>", "<iban#1>", "<iccid#1>", "<nid#1>", "<address#1>");
        assertThat(provider.system).isEqualTo(SYSTEM);
        // the caller still gets the real values back in the answer
        assertThat(answer).isEqualTo(
                "Reply to anna@example.com at +47 912 34 567 about the SIM 8947000000000000001.");

        AiAudit row = ledgerRow();
        assertThat(row.getRawExposure()).isFalse();
        assertThat(row.getRedactedFields()).isEqualTo(6);
        assertThat(row.getExposure()).isEqualTo("raw-redacted");
        assertThat(row.getPrompt()).doesNotContain("anna@example.com").contains("<email#1>");
        assertThat(row.getResponse()).doesNotContain("anna@example.com").contains("<email#1>");
        assertThat(row.getOutcome()).isEqualTo("ok");
    }

    @Test
    void withRawExposureOnTheProviderSeesThePromptAsWrittenAndTheLedgerSaysSo() {
        String answer = governor(true).complete("ticket-reply", LlmAdapter.Tier.FAST, SYSTEM, USER);

        assertThat(provider.user).contains("anna@example.com", "+47 912 34 567",
                "NO93 8601 1117 947", "8947000000000000001", "01019912345", "Storgata 12");
        assertThat(answer).startsWith("Reply to <email#1>"); // the model's words, untouched

        AiAudit row = ledgerRow();
        assertThat(row.getRawExposure()).isTrue();
        assertThat(row.getRedactedFields()).isEqualTo(6);
        assertThat(row.getExposure()).isEqualTo("raw");
        // the ledger copy is redacted regardless of what left
        assertThat(row.getPrompt()).doesNotContain("anna@example.com").contains("<email#1>");
    }

    @Test
    void aRefusalIsLedgeredRedactedToo() {
        // the kill switch: a budget row that is disabled
        com.bss.intelligence.audit.AiBudget off = new com.bss.intelligence.audit.AiBudget();
        off.setTenantId("t1");
        off.setEnabled(false);
        AiBudgetRepository budgets = mock(AiBudgetRepository.class);
        when(budgets.findByTenantId(anyString())).thenReturn(Optional.of(off));
        TenantScope scope = mock(TenantScope.class);
        when(scope.currentTenantId()).thenReturn("t1");
        AiGovernor killed = new AiGovernor(provider, audits, budgets, mock(AiContractRepository.class),
                scope, new Redactor(), mock(PlatformTransactionManager.class), 2000L,
                new TenantRegistry(), new MockEnvironment());
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> killed.complete("ticket-reply", LlmAdapter.Tier.FAST, SYSTEM, USER))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(provider.user).isNull(); // nothing left
        AiAudit row = ledgerRow();
        assertThat(row.getOutcome()).isEqualTo("refused-disabled");
        assertThat(row.getPrompt()).doesNotContain("anna@example.com");
    }
}
