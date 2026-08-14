package com.bss.insight.service;

import com.bss.insight.client.SocialAudienceClient;
import com.bss.insight.entity.PartyTrait;
import com.bss.insight.repository.PartyTraitRepository;
import com.bss.insight.security.TenantScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Activate an audience OUT to an ad/social platform — the acquisition half of
 * marketing. Two modes, one mechanism:
 * <ul>
 *   <li><b>seed</b> — push a high-value first-party audience so the platform
 *       builds a LOOKALIKE and finds strangers who resemble them.</li>
 *   <li><b>suppress</b> — push existing customers so paid spend EXCLUDES people
 *       you already have.</li>
 * </ul>
 * Emails resolve from the member set with no per-row fan-out: a prospect carries
 * its own address; a customer's email is read from the denormalised trait store
 * in one query. Hashing + batching live in {@link SocialAudienceClient}.
 */
@Service
public class ActivationService {

    private final AudienceService audiences;
    private final SocialAudienceClient social;
    private final PartyTraitRepository traits;
    private final TenantScope tenantScope;

    public ActivationService(AudienceService audiences, SocialAudienceClient social,
            PartyTraitRepository traits, TenantScope tenantScope) {
        this.audiences = audiences;
        this.social = social;
        this.traits = traits;
        this.tenantScope = tenantScope;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> activate(String audienceId, Map<String, Object> body) {
        String externalAudienceId = body.get("externalAudienceId") == null
                ? null : String.valueOf(body.get("externalAudienceId"));
        String mode = "suppress".equals(body.get("mode")) ? "suppress" : "seed";
        if (externalAudienceId == null || externalAudienceId.isBlank()) {
            throw new IllegalArgumentException("externalAudienceId (the platform Custom Audience id) is required");
        }
        List<Map<String, Object>> members = audiences.members(audienceId);
        // Customer members carry a partyId; resolve their emails in ONE query.
        Map<String, String> emailByParty = null;
        List<String> emails = new ArrayList<>();
        for (Map<String, Object> m : members) {
            Object email = m.get("email");
            if (email != null && !String.valueOf(email).isBlank()) {
                emails.add(String.valueOf(email));
                continue;
            }
            Object partyId = m.get("partyId");
            if (partyId != null) {
                if (emailByParty == null) {
                    emailByParty = new LinkedHashMap<>();
                    for (PartyTrait t : traits.findByTenantIdAndTraitKey(tenantScope.currentTenantId(), "email")) {
                        emailByParty.putIfAbsent(t.getPartyId(), t.getTraitValue());
                    }
                }
                String e = emailByParty.get(String.valueOf(partyId));
                if (e != null) emails.add(e);
            }
        }
        int pushed = social.pushCustomAudience(externalAudienceId, emails);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("audienceId", audienceId);
        out.put("externalAudienceId", externalAudienceId);
        out.put("mode", mode);                 // seed = lookalike source, suppress = exclusion
        out.put("members", members.size());
        out.put("pushed", pushed);             // hashed identifiers accepted by the platform
        out.put("skipped", members.size() - emails.size()); // members with no resolvable email
        out.put("enabled", social.enabled());
        return out;
    }
}
