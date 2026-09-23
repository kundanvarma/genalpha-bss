package com.bss.communication;

import com.bss.communication.security.TenantRegistry;
import com.bss.communication.security.TenantScope;
import com.bss.communication.service.UnsubscribeToken;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * An unsubscribe link is honoured with no login, so the signature in it is the
 * whole of the security. Two things were wrong and both were one secret for
 * everybody: the signing key was deployment-wide with a literal default in the
 * source, which in a source-available repository means forging an unsubscribe
 * for any customer is arithmetic; and the signature covered the party alone, so
 * one operator's link verified for another.
 *
 * The delicate part is that links already sit in inboxes. An unsubscribe that
 * stops working is the one failure a marketing system may never have, so the
 * old form is still honoured while the operator keeps the old secret on
 * purpose — and nothing mints it any more.
 */
class UnsubscribeTokenTest {

    private static final String PARTY = "party-paula";

    private static TenantRegistry.TenantEntry entry(String secret) {
        TenantRegistry.TenantEntry e = new TenantRegistry.TenantEntry();
        e.setUnsubscribeSecret(secret);
        return e;
    }

    /** A token minter standing in one tenant, with the registry it can see. */
    private static UnsubscribeToken tokenFor(String tenantId, String legacySecret) {
        TenantRegistry tenants = mock(TenantRegistry.class);
        given(tenants.byId("alpha")).willReturn(entry("alpha-own-secret"));
        given(tenants.byId("beta")).willReturn(entry("beta-own-secret"));
        given(tenants.byId("nameless")).willReturn(entry(null));
        TenantScope scope = mock(TenantScope.class);
        given(scope.currentTenantId()).willReturn(tenantId);
        return new UnsubscribeToken(tenants, scope, "", legacySecret, "http://shop.example");
    }

    @Test
    void anOperatorsOwnLinkVerifies() {
        UnsubscribeToken alpha = tokenFor("alpha", "");

        assertThat(alpha.valid(PARTY, alpha.forParty(PARTY))).isTrue();
        assertThat(alpha.linkFor(PARTY)).startsWith("http://shop.example/esp/v1/unsubscribe?p=" + PARTY + "&t=");
    }

    @Test
    void oneOperatorsLinkDoesNotWorkOnAnother() {
        // the same customer id in two operators is not the same person, and a
        // leaked secret must stop at the operator it belongs to
        String alphaToken = tokenFor("alpha", "").forParty(PARTY);

        assertThat(tokenFor("beta", "").valid(PARTY, alphaToken)).isFalse();
    }

    @Test
    void aForgedOrEmptyTokenIsRefused() {
        UnsubscribeToken alpha = tokenFor("alpha", "");

        assertThat(alpha.valid(PARTY, "000000000000000000000000")).isFalse();
        assertThat(alpha.valid(PARTY, "")).isFalse();
        assertThat(alpha.valid(PARTY, null)).isFalse();
        assertThat(alpha.valid(null, "whatever")).isFalse();
        // somebody else's party under a token minted for ours
        assertThat(alpha.valid("party-someone-else", alpha.forParty(PARTY))).isFalse();
    }

    @Test
    void aLinkAlreadyInAnInboxKeepsWorkingWhileTheOldSecretIsKept() {
        String oldSecret = "the-secret-that-signed-last-months-newsletter";
        String legacyToken = legacyForm(oldSecret, PARTY);

        assertThat(tokenFor("alpha", oldSecret).valid(PARTY, legacyToken)).isTrue();
    }

    @Test
    void theLegacyFormIsRefusedOnceTheOldSecretIsRetired() {
        String oldSecret = "the-secret-that-signed-last-months-newsletter";
        String legacyToken = legacyForm(oldSecret, PARTY);

        assertThat(tokenFor("alpha", "").valid(PARTY, legacyToken)).isFalse();
    }

    @Test
    void theRetiredSourceDefaultNoLongerSignsAnything() {
        // the literal that used to sit in this file, and in the repository
        String published = legacyForm("genalpha-dev-unsub-secret", PARTY);

        assertThat(tokenFor("alpha", "").valid(PARTY, published)).isFalse();
    }

    @Test
    void anOperatorWithNoSecretMintsNothingRatherThanSigningWithAKnownValue() {
        assertThatThrownBy(() -> tokenFor("nameless", "").forParty(PARTY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no unsubscribe secret configured");
    }

    /** The old scheme: HMAC over the party id alone, first 96 bits, hex. */
    private static String legacyForm(String secret, String partyId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal(partyId.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : sig) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.substring(0, 24);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
