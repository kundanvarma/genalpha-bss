package com.bss.usage;

import com.bss.usage.service.OcsNotificationAuth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The credential on the OCS → BSS door. This door rates GB onto a bill
 * ({@code priorityUsage}) and answers the verdict that permits or refuses a
 * charge ({@code spendThreshold}), and the tenant rides the body — so the
 * body has to be signed with that tenant's own secret, or anything on the
 * private network can write charges into any operator's books.
 *
 * <p>Three things are proven per door: a correctly-signed call is accepted, an
 * uncredentialled one is 401, and a call signed with tenant A's secret cannot
 * act on tenant B.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OcsNotificationDoorTest {

    private static final String GENALPHA = "test-ocs-secret-genalpha";
    private static final String TENANT_A = "test-ocs-secret-tenant-a";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OcsNotificationAuth auth;

    /* ------------------------------------------------- usageThreshold */

    @Test
    void aSignedThresholdNotificationIsAccepted() throws Exception {
        String body = """
                {"tenantId": "tenant-a", "partyId": "door-a", "percentUsed": 90, "threshold": 80}""";
        mockMvc.perform(post("/internal/ocs/usageThreshold")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(OcsNotificationAuth.SIGNATURE_HEADER, OcsSignature.of(TENANT_A, body))
                        .content(body))
                .andExpect(status().isAccepted());
    }

    @Test
    void anUncredentialledThresholdNotificationIsRefused() throws Exception {
        String body = """
                {"tenantId": "tenant-a", "partyId": "door-a", "percentUsed": 90, "threshold": 80}""";
        mockMvc.perform(post("/internal/ocs/usageThreshold")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tenantAsSecretCannotNotifyForTenantB() throws Exception {
        String body = """
                {"tenantId": "tenant-b", "partyId": "door-b", "percentUsed": 90, "threshold": 80}""";
        mockMvc.perform(post("/internal/ocs/usageThreshold")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(OcsNotificationAuth.SIGNATURE_HEADER, OcsSignature.of(TENANT_A, body))
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aSignatureOverADifferentBodyIsRefused() throws Exception {
        String signed = """
                {"tenantId": "tenant-a", "partyId": "door-a", "percentUsed": 90, "threshold": 80}""";
        String sent = """
                {"tenantId": "tenant-a", "partyId": "somebody-else", "percentUsed": 90, "threshold": 80}""";
        mockMvc.perform(post("/internal/ocs/usageThreshold")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(OcsNotificationAuth.SIGNATURE_HEADER, OcsSignature.of(TENANT_A, signed))
                        .content(sent))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aStaleSignatureIsRefused() throws Exception {
        String body = """
                {"tenantId": "tenant-a", "partyId": "door-a", "percentUsed": 90, "threshold": 80}""";
        String old = OcsSignature.at(TENANT_A, body, System.currentTimeMillis() - 3_600_000L);
        mockMvc.perform(post("/internal/ocs/usageThreshold")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(OcsNotificationAuth.SIGNATURE_HEADER, old)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aTenantWithNoSecretHasAShutDoor() throws Exception {
        String body = """
                {"tenantId": "tenant-c", "partyId": "door-c", "percentUsed": 90, "threshold": 80}""";
        mockMvc.perform(post("/internal/ocs/usageThreshold")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(OcsNotificationAuth.SIGNATURE_HEADER, OcsSignature.of(TENANT_A, body))
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aTenantNobodyHasRegisteredIsRefused() throws Exception {
        String body = """
                {"tenantId": "not-an-operator", "partyId": "door-x", "percentUsed": 90, "threshold": 80}""";
        mockMvc.perform(post("/internal/ocs/usageThreshold")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(OcsNotificationAuth.SIGNATURE_HEADER, OcsSignature.of(TENANT_A, body))
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    /* --------------------------------------------------- priorityUsage */

    @Test
    void priorityUsageNeedsTheTenantsOwnSecret() throws Exception {
        String body = """
                {"tenantId": "tenant-a", "partyId": "door-a", "gb": 5, "upliftPerGb": 0.5, "currency": "EUR"}""";
        mockMvc.perform(post("/internal/ocs/priorityUsage")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/internal/ocs/priorityUsage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(OcsNotificationAuth.SIGNATURE_HEADER, OcsSignature.of(GENALPHA, body))
                        .content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/internal/ocs/priorityUsage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(OcsNotificationAuth.SIGNATURE_HEADER, OcsSignature.of(TENANT_A, body))
                        .content(body))
                .andExpect(status().isAccepted());
    }

    /* --------------------------------------------------- spendThreshold */

    @Test
    void spendThresholdNeedsTheTenantsOwnSecret() throws Exception {
        String body = """
                {"tenantId": "tenant-a", "partyId": "door-a", "chargeClass": "usage",
                 "amount": {"value": 3.0, "unit": "EUR"}}""";
        mockMvc.perform(post("/internal/ocs/spendThreshold")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/internal/ocs/spendThreshold")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(OcsNotificationAuth.SIGNATURE_HEADER, OcsSignature.of(GENALPHA, body))
                        .content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/internal/ocs/spendThreshold")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(OcsNotificationAuth.SIGNATURE_HEADER, OcsSignature.of(TENANT_A, body))
                        .content(body))
                .andExpect(status().isAccepted());
    }

    /* ------------------------------------------------ the SigScale hub */

    @Test
    void theSigscaleHubCallbackCarriesItsCredentialInThePath() throws Exception {
        String body = """
                {"event": []}""";
        String tokenA = auth.callbackToken("tenant-a");
        String tokenB = auth.callbackToken("tenant-b");
        assertThat(tokenA).isNotBlank().isNotEqualTo(tokenB);

        mockMvc.perform(post("/internal/ocs/sigscale/tenant-a/" + tokenA)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());
        // tenant B's token is not a key to tenant A's door
        mockMvc.perform(post("/internal/ocs/sigscale/tenant-a/" + tokenB)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        // and the door that used to exist without a token is gone
        mockMvc.perform(post("/internal/ocs/sigscale/tenant-a")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
    }
}
