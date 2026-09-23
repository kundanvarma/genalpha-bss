package com.bss.communication;

import com.bss.communication.repository.SuppressionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The credential on the ESP's receipt door ({@code POST /esp/v1/event}).
 *
 * <p>The door answers 200 to everything on purpose — a webhook that gets a
 * refusal retries for ever, and a poisoned batch must not wedge the
 * provider's queue — so "accepted" is the count in the body, and the wall is
 * proven by what the batch <i>did</i>, not by the status code. A suppression
 * row is permanent: it stops an address being emailed for that tenant, which
 * is exactly what an uncredentialled caller must not be able to create.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EspReceiptDoorTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SuppressionRepository suppressions;

    private static String bounce(String tenantId, String email) {
        return """
                [{"event": "bounce", "email": "%s",
                  "custom_args": {"tenant": "%s", "messageId": "no-such-message"}}]"""
                .formatted(email, tenantId);
    }

    @Test
    void theInboundWebhookSecretIsWhatOpensTheDoor() throws Exception {
        // tenant-a has moved to a separate inbound credential
        mockMvc.perform(post("/esp/v1/event").header("X-Esp-Token", "test-esp-webhook-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bounce("tenant-a", "webhook-secret@door.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(1));
        assertThat(suppressions.existsByTenantIdAndEmail("tenant-a", "webhook-secret@door.test")).isTrue();
    }

    @Test
    void oneTenantsWebhookSecretDoesNotOpenAnothersDoor() throws Exception {
        mockMvc.perform(post("/esp/v1/event").header("X-Esp-Token", "test-esp-webhook-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bounce("tenant-b", "crossed@door.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(0));
        assertThat(suppressions.existsByTenantIdAndEmail("tenant-b", "crossed@door.test")).isFalse();
    }

    @Test
    void aTenantStillOnTheSendingKeyKeepsWorkingAcrossTheChange() throws Exception {
        // tenant-b has no webhook secret yet: an existing integration must not
        // break on upgrade, and the acceptance is logged as the outbound key
        mockMvc.perform(post("/esp/v1/event").header("X-Esp-Token", "test-esp-key-b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bounce("tenant-b", "legacy-path@door.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(1));
        assertThat(suppressions.existsByTenantIdAndEmail("tenant-b", "legacy-path@door.test")).isTrue();
    }

    @Test
    void theSendingKeyStopsOpeningTheDoorOnceTheInboundOneIsSet() throws Exception {
        // tenant-a has configured esp-webhook-secret, so its OUTBOUND key is no
        // longer a way in — otherwise splitting the credentials buys nothing
        mockMvc.perform(post("/esp/v1/event").header("X-Esp-Token", "test-esp-key-a")
                        .contentType(MediaType.APPLICATION_JSON).content(bounce("tenant-a", "ok@door.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(0));
        assertThat(suppressions.existsByTenantIdAndEmail("tenant-a", "ok@door.test")).isFalse();
    }

    @Test
    void aBatchWithNoCredentialSuppressesNothing() throws Exception {
        mockMvc.perform(post("/esp/v1/event")
                        .contentType(MediaType.APPLICATION_JSON).content(bounce("tenant-a", "none@door.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(0));
        assertThat(suppressions.existsByTenantIdAndEmail("tenant-a", "none@door.test")).isFalse();
    }

    @Test
    void tenantAsKeyCannotSuppressForTenantB() throws Exception {
        mockMvc.perform(post("/esp/v1/event").header("X-Esp-Token", "test-esp-webhook-a")
                        .contentType(MediaType.APPLICATION_JSON).content(bounce("tenant-b", "cross@door.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(0));
        assertThat(suppressions.existsByTenantIdAndEmail("tenant-b", "cross@door.test")).isFalse();
    }

    /**
     * The hole this test was written for. A tenant with no ESP relationship
     * carries an empty {@code esp-api-key}; the door used to compare it with
     * {@code String.equals} against the presented header, so an EMPTY
     * {@code X-Esp-Token} matched and opened that tenant's suppression ledger
     * to any caller who could reach the service. Blank is now a refusal.
     */
    @Test
    void anEmptyTokenDoesNotMatchATenantWithNoEspKey() throws Exception {
        mockMvc.perform(post("/esp/v1/event").header("X-Esp-Token", "")
                        .contentType(MediaType.APPLICATION_JSON).content(bounce("genalpha", "blank@door.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(0));
        assertThat(suppressions.existsByTenantIdAndEmail("genalpha", "blank@door.test")).isFalse();

        mockMvc.perform(post("/esp/v1/event").header("X-Esp-Token", "   ")
                        .contentType(MediaType.APPLICATION_JSON).content(bounce("genalpha", "space@door.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(0));
        assertThat(suppressions.existsByTenantIdAndEmail("genalpha", "space@door.test")).isFalse();
    }
}
