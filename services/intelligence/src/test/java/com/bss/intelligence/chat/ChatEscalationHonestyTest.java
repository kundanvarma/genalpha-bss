package com.bss.intelligence.chat;

import com.bss.intelligence.client.BssApiClient;
import com.bss.intelligence.llm.AiGovernor;
import com.bss.intelligence.security.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * Found by the care chat evals (#422): with the ticket system down, the chat told
 * the customer "I've raised ticket null — a human will pick this up" and then fell
 * silent, waiting for a human nobody had asked. No ticket, no promise.
 */
@SpringBootTest
@ActiveProfiles("test")
class ChatEscalationHonestyTest {

    @Autowired
    private CareChatService chat;

    @MockitoBean
    private AiGovernor governor;

    @MockitoBean
    private BssApiClient bss;

    @Test
    void whenNoTicketCanBeRaised_nothingIsPromised_andTheChatStaysOpen() {
        given(bss.offeringsFirstPage()).willReturn(List.of());
        given(bss.openTicket(anyString(), anyString(), any())).willReturn(null);
        given(governor.complete(anyString(), any(), anyString(), anyString())).willReturn("[ESCALATE]");
        try (TenantContext ignored = TenantContext.actAs("genalpha")) {
            CareChatSession s = chat.open(null);
            ChatViews.ChatTurn turn = chat.customerMessage(s, "I want a human please");
            assertThat(turn.reply()).isEqualTo(CareChatService.NO_TICKET).doesNotContain("null");
            assertThat(turn.ticketId()).isNull();
            assertThat(turn.status()).isEqualTo("open");
            ChatViews.EscalationReceipt direct = chat.escalate(s, null);
            assertThat(direct.ticketId()).isNull();
            assertThat(direct.status()).isEqualTo("open");
        }
    }
}
