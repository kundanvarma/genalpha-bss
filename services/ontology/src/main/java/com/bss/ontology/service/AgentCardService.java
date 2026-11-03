package com.bss.ontology.service;

import com.bss.ontology.api.ApiConstants;
import com.bss.ontology.dto.A2aMessages;
import com.bss.ontology.dto.A2aMessages.AgentCard;
import com.bss.ontology.dto.A2aMessages.AgentInterface;
import com.bss.ontology.dto.A2aMessages.AuthorizationCodeFlow;
import com.bss.ontology.dto.A2aMessages.OAuth2SecurityScheme;
import com.bss.ontology.dto.A2aMessages.OAuthFlows;
import com.bss.ontology.dto.A2aMessages.SecurityRequirement;
import com.bss.ontology.dto.A2aMessages.SecurityScheme;
import com.bss.ontology.dto.A2aMessages.StringList;
import com.bss.ontology.security.AgentCardSigner;
import com.bss.ontology.security.ProtectedResourceMetadata;
import com.bss.ontology.security.TenantRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The tenant's Agent Card (A2A 1.0): who this agent is, where its JSON-RPC
 * endpoint is, how to get a token, and the skills it actually serves — read off
 * {@link A2aSkills#offered(String)}, the same list the door enforces, so the card
 * cannot advertise what the door would refuse.
 *
 * <p>The tenant is the caller's {@link com.bss.ontology.security.TenantScope}: for
 * this anonymous read, the gateway's hostname stamp, else the deployment default —
 * never a query parameter or a body. The OAuth scheme names THAT tenant's issuer,
 * the same registry entry that validates the token afterwards (as the MCP door's
 * RFC 9728 metadata does), so a partner of one operator is never sent to another
 * operator's login. The URLs echo the public origin the caller came through; they
 * decide nothing.
 */
@Service
public class AgentCardService {

    public static final String CARD_VERSION = "1.0.0";

    private final A2aSkills skills;
    private final TenantRegistry tenants;
    private final AgentCardSigner signer;
    private final ObjectMapper json;

    public AgentCardService(A2aSkills skills, TenantRegistry tenants, AgentCardSigner signer, ObjectMapper json) {
        this.skills = skills;
        this.tenants = tenants;
        this.signer = signer;
        this.json = json;
    }

    /** The card for {@code tenant} as seen through {@code request}'s origin, signed when a key is configured. */
    public AgentCard card(String tenant, HttpServletRequest request) {
        String origin = ProtectedResourceMetadata.origin(request);
        TenantRegistry.TenantEntry entry = tenants.byId(tenant);
        String issuer = entry == null || entry.getIssuer() == null ? "" : entry.getIssuer().replaceAll("/+$", "");
        Map<String, SecurityScheme> schemes = new LinkedHashMap<>();
        List<SecurityRequirement> requirements = List.of();
        if (!issuer.isEmpty()) {
            // Keycloak's endpoint layout under the realm issuer; the metadata URL is the issuer's own discovery document
            schemes.put("oauth2", new SecurityScheme(new OAuth2SecurityScheme(
                    "Sign the person in at this operator's identity server and send their access token as a bearer. "
                            + "The agent acts with that person's rights and no others; an agent that is not named by the "
                            + "token is receipted as declared, not verified.",
                    new OAuthFlows(new AuthorizationCodeFlow(issuer + "/protocol/openid-connect/auth",
                            issuer + "/protocol/openid-connect/token", Map.of())),
                    issuer + "/.well-known/openid-configuration")));
            requirements = List.of(new SecurityRequirement(Map.of("oauth2", new StringList(List.of()))));
        }
        AgentCard card = new AgentCard(
                "GenAlpha BSS — governed actions",
                "Delegate a task to this operator's BSS: ask whether a household can pick a benefit, or have a governed "
                        + "action explained. Every task runs through the operator's registry of governed actions — each "
                        + "condition checked with the caller's own token, refused in words when it fails — and anything "
                        + "that acts writes a decision receipt.",
                List.of(new AgentInterface(origin + ApiConstants.A2A_PATH, A2aMessages.BINDING_JSONRPC, A2aMessages.PROTOCOL_VERSION)),
                CARD_VERSION, null,
                new A2aMessages.Capabilities(false, false, false),
                schemes, requirements,
                List.of("application/json"), List.of("application/json", "text/plain"),
                skills.offered(tenant), null);
        if (!signer.enabled()) {
            return card;
        }
        JsonNode unsigned = json.valueToTree(card);
        return signer.sign(unsigned).map(s -> card.signed(List.of(s))).orElse(card);
    }
}
