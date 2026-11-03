package com.bss.campaign.service;

import com.bss.campaign.client.CommunicationClient.AiMark;
import com.bss.campaign.exception.BadRequestException;
import com.bss.campaign.security.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The server decides the AI mark (EU AI Act Art. 50(2), #422).
 *
 * <p>Intelligence issues every AI draft with a receipt, {@code aiDraft} =
 * {@code aid1.<payload>.<signature>}: an HMAC-SHA256 under the operator's
 * provenance key over {@code {tenant, model, draft, issuedAt}}. Copy that
 * arrives with a receipt is marked here — {@code aiGenerated: true}, and the
 * model the receipt names — whatever the body says, so a caller cannot clear
 * the mark on copy that came from a draft by sending {@code aiGenerated: false}.
 * A receipt that does not verify (another key, another tenant, an edited
 * payload, junk) is refused with a 400: a false provenance is not stored.
 * Copy with no receipt keeps the body's declaration, as before; copy with
 * neither is human.
 *
 * <p>Without a key (the application default) a receipt cannot be checked; it
 * still marks the copy, with the declared model — a receipt can only ever add
 * a mark. What this does not prove: which words the model wrote (the text is
 * not bound — edited copy stays AI-assisted), and nothing at all about copy
 * that arrives without a receipt, such as text pasted in from outside.
 */
@Component
public class AiProvenance {

    private static final Logger log = LoggerFactory.getLogger(AiProvenance.class);
    private static final String PREFIX = "aid1.";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** The keys that carry the mark on a piece of copy, wherever it sits. */
    private static final List<String> MARK = List.of("aiGenerated", "aiModel", "aiDraft");

    private final byte[] key;
    private final TenantScope tenantScope;

    public AiProvenance(@Value("${bss.ai.provenance-key:}") String key, TenantScope tenantScope) {
        this.key = key == null ? new byte[0] : key.getBytes(StandardCharsets.UTF_8);
        this.tenantScope = tenantScope;
    }

    /**
     * The mark for one piece of copy: from its receipt when it has one, else from
     * what the body declared; null = human copy.
     */
    public AiMark markFor(String aiDraft, Boolean declared, String declaredModel) {
        String model = blankToNull(declaredModel);
        if (aiDraft == null || aiDraft.isBlank()) {
            return Boolean.TRUE.equals(declared) || model != null ? new AiMark(model) : null;
        }
        if (key.length == 0) {
            log.warn("an AI draft receipt arrived but bss.ai.provenance-key is not set — marked, not verified");
            return new AiMark(model);
        }
        return new AiMark(verifiedModel(aiDraft.trim()));
    }

    /** The model a receipt names, after checking signature and tenant; a 400 otherwise. */
    private String verifiedModel(String token) {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || !token.startsWith(PREFIX)) {
            throw refused();
        }
        byte[] expected = hmac(PREFIX + parts[1]);
        byte[] given;
        JsonNode payload;
        try {
            given = Base64.getUrlDecoder().decode(parts[2]);
            payload = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
        } catch (RuntimeException e) {
            throw refused();
        }
        if (!MessageDigest.isEqual(expected, given)
                || !tenantScope.currentTenantId().equals(payload.path("tenant").asString(""))) {
            throw refused();
        }
        return blankToNull(payload.path("model").asString(""));
    }

    private byte[] hmac(String signed) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(signed.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }

    private static BadRequestException refused() {
        return new BadRequestException("aiDraft is not an AI draft receipt this operator issued");
    }

    /**
     * Set the mark on every piece of journey copy that carries a receipt: a
     * message step, a waitForEvent's onTimeout, a decision's then/else. Copy
     * without a receipt is left as its author declared it.
     */
    public void stampSteps(List<Map<String, Object>> steps) {
        for (Map<String, Object> step : steps) {
            String type = String.valueOf(step.get("type"));
            if ("message".equals(type)) {
                stamp(step);
            } else if ("waitForEvent".equals(type)) {
                stampNested(step.get("onTimeout"));
            } else if ("decision".equals(type) || "branch".equals(type)) {
                stampNested(step.get("then"));
                stampNested(step.get("else"));
            }
        }
    }

    /** An A/B arm is copy too: an arm drafted by a model and sent with its receipt is marked. */
    public void stampArms(List<Map<String, Object>> arms) {
        arms.forEach(this::stamp);
    }

    @SuppressWarnings("unchecked")
    private void stampNested(Object copy) {
        if (copy instanceof Map<?, ?> m) {
            stamp((Map<String, Object>) m);
        }
    }

    private void stamp(Map<String, Object> copy) {
        Object draft = copy.get("aiDraft");
        if (draft == null) {
            return;
        }
        if (!(draft instanceof String token)) {
            throw refused();
        }
        Object declaredModel = copy.get("aiModel");
        AiMark mark = markFor(token, true, declaredModel instanceof String s ? s : null);
        copy.put("aiGenerated", true);
        if (mark.model() != null) {
            copy.put("aiModel", mark.model());
        }
    }

    /**
     * The step as it is spoken in an A/B arm, carrying the mark of the text
     * actually sent: an arm that replaces all the copy (subject and content, or
     * a template) speaks with its own provenance; an arm that replaces only part
     * of it still sends the rest of the step's copy, so the send is marked if
     * either the arm or the step it borrows from was written by a model.
     */
    static Map<String, Object> spokenInArm(Map<String, Object> step, Map<String, Object> arm) {
        boolean replacesAll = arm.get("templateRef") != null
                || (arm.get("subject") != null && arm.get("content") != null);
        AiMark armMark = JourneyService.aiMarkOf(arm);
        AiMark stepMark = replacesAll ? null : JourneyService.aiMarkOf(step);
        Map<String, Object> spoken = new LinkedHashMap<>(step);
        MARK.forEach(spoken::remove);
        for (String k : List.of("subject", "content", "templateRef")) {
            if (arm.get(k) != null) {
                spoken.put(k, arm.get(k));
            }
        }
        if (armMark != null || stepMark != null) {
            spoken.put("aiGenerated", true);
            String model = armMark != null && armMark.model() != null ? armMark.model()
                    : stepMark != null ? stepMark.model() : null;
            if (model != null) {
                spoken.put("aiModel", model);
            }
        }
        return spoken;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
