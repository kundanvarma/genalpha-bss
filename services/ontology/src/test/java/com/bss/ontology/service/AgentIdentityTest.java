package com.bss.ontology.service;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Which agent is acting is proven by the token, not by the agent's word (#422).
 * A token-exchange token's act claim names the agent; the X-GenAlpha-Agent header
 * cannot rename it. With no act claim the header still names the agent, marked
 * as only declared.
 */
class AgentIdentityTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void signIn(Map<String, Object> extraClaims) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "none").subject("paula")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        extraClaims.forEach(b::claim);
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(b.build(), List.of(new SimpleGrantedAuthority("customer"))));
    }

    private static HttpServletRequest withAgentHeader(String name) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        given(req.getHeader(Caller.AGENT_HEADER)).willReturn(name);
        return req;
    }

    @Test
    void theTokensActorWins_andIsVerified() {
        signIn(Map.of("act", Map.of("client_id", "shopping-agent-7")));
        Caller c = Caller.current(withAgentHeader("care-assist"), "genalpha");
        assertThat(c.agent()).isEqualTo("shopping-agent-7");
        assertThat(c.agentVerified()).isTrue();
        assertThat(c.agentEvidence()).isEqualTo("verified");
        assertThat(c.subject()).isEqualTo("paula");   // still acting FOR the person
    }

    @Test
    void aHeaderAloneIsOnlyDeclared() {
        signIn(Map.of());
        Caller c = Caller.current(withAgentHeader("care-assist"), "genalpha");
        assertThat(c.agent()).isEqualTo("care-assist");
        assertThat(c.agentEvidence()).isEqualTo("declared");
        assertThat(Caller.current(withAgentHeader(null), "genalpha").agentEvidence()).isEqualTo("none");
    }

    /** The gateway verified a Web Bot Auth signature and stamped the operator (#427). */
    private static HttpServletRequest gatewayVerified(String operator, String declared) {
        HttpServletRequest req = withAgentHeader(declared);
        given(req.getHeader(Caller.VERIFIED_AGENT_HEADER)).willReturn(operator);
        return req;
    }

    @Test
    void aGatewayVerifiedSignatureIsVerifiedEvidence_andOutranksTheHeader() {
        signIn(Map.of());
        Caller c = Caller.current(gatewayVerified("example-agents", "care-assist"), "genalpha");
        assertThat(c.agent()).isEqualTo("example-agents");
        assertThat(c.agentVerified()).isTrue();
        assertThat(c.agentEvidence()).isEqualTo("verified");
        assertThat(c.subject()).isEqualTo("paula");
    }

    @Test
    void theTokensActorStillWinsOverTheGatewayStamp() {
        signIn(Map.of("act", Map.of("client_id", "shopping-agent-7")));
        Caller c = Caller.current(gatewayVerified("example-agents", null), "genalpha");
        assertThat(c.agent()).isEqualTo("shopping-agent-7");
        assertThat(c.agentEvidence()).isEqualTo("verified");
    }

    @Test
    void aBlankGatewayStampIsNoEvidence() {
        signIn(Map.of());
        Caller c = Caller.current(gatewayVerified("  ", "care-assist"), "genalpha");
        assertThat(c.agent()).isEqualTo("care-assist");
        assertThat(c.agentEvidence()).isEqualTo("declared");
    }
}
