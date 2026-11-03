package com.bss.intelligence.service;

import com.bss.intelligence.security.TenantScope;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

/**
 * A provenance receipt for copy a model drafted (EU AI Act Art. 50(2), #422).
 *
 * <p>The receipt is {@code aid1.<payload>.<signature>}: the payload is
 * {@code {tenant, model, draft, issuedAt}} in base64url, the signature an
 * HMAC-SHA256 over {@code "aid1." + payload} under the operator's provenance key
 * ({@code bss.ai.provenance-key}, the same key the campaign component verifies
 * with). It travels with the copy as {@code aiDraft}; campaign checks it and sets
 * the AI mark itself, so a request body can no longer clear the mark on copy that
 * came from a draft.
 *
 * <p>What it proves: this operator's intelligence component issued a draft with
 * that model. What it does not: which words — the text is not bound, because a
 * marketer edits the draft and edited copy is still AI-assisted. Text pasted in
 * from outside the system carries no receipt and cannot be detected.
 *
 * <p>No key, no receipt: the application default is off and copy then carries
 * only the declared mark, as before. The fleet opts in in docker-compose.yml.
 */
@Component
public class AiDraftProvenance {

    public static final String PREFIX = "aid1.";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final byte[] key;
    private final TenantScope tenantScope;

    public AiDraftProvenance(@Value("${bss.ai.provenance-key:}") String key, TenantScope tenantScope) {
        this.key = key == null ? new byte[0] : key.getBytes(StandardCharsets.UTF_8);
        this.tenantScope = tenantScope;
    }

    /** The receipt for one draft by this provider/model, for the caller's tenant; null when no key is set. */
    public String issue(String provider, String model) {
        if (key.length == 0) {
            return null;
        }
        return sign(key, tenantScope.currentTenantId(), modelName(provider, model), UUID.randomUUID().toString(),
                Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
    }

    /** {@code provider/model}, the way every mark in the fleet names a model. */
    public static String modelName(String provider, String model) {
        if (provider == null || provider.isBlank()) {
            return model;
        }
        return model == null || model.isBlank() ? provider : provider + "/" + model;
    }

    @JsonPropertyOrder({"tenant", "model", "draft", "issuedAt"})
    record Payload(String tenant, String model, String draft, String issuedAt) {
    }

    /** The receipt itself — public so a test can pin the format both components share. */
    public static String sign(byte[] key, String tenant, String model, String draft, String issuedAt) {
        String payload = B64.encodeToString(JSON.writeValueAsBytes(new Payload(tenant, model, draft, issuedAt)));
        String signed = PREFIX + payload;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return signed + "." + B64.encodeToString(mac.doFinal(signed.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }

    /**
     * Mark every piece of copy in a copilot proposal (a campaign's message; a
     * journey's message steps, a waitForEvent's onTimeout, a decision's then/else):
     * {@code aiGenerated: true}, {@code aiModel}, and the receipt when there is one.
     * The proposal is what the console posts on Create, so it leaves here marked.
     */
    public static void markProposal(JsonNode proposal, String model, String token) {
        if (proposal == null || !proposal.isObject()) {
            return;
        }
        mark(proposal.path("campaign").path("message"), model, token);
        for (JsonNode step : proposal.path("journey").path("steps")) {
            String type = step.path("type").asString("");
            if ("message".equals(type)) {
                mark(step, model, token);
            } else if ("waitForEvent".equals(type)) {
                mark(step.path("onTimeout"), model, token);
            } else if ("decision".equals(type) || "branch".equals(type)) {
                mark(step.path("then"), model, token);
                mark(step.path("else"), model, token);
            }
        }
    }

    private static void mark(JsonNode copy, String model, String token) {
        if (!(copy instanceof ObjectNode node) || !(node.has("subject") || node.has("content"))) {
            return;
        }
        node.put("aiGenerated", true);
        if (model != null) {
            node.put("aiModel", model);
        }
        if (token != null) {
            node.put("aiDraft", token);
        }
    }
}
