package com.bss.campaign.service;

import com.bss.campaign.client.InsightClient;
import com.bss.campaign.client.SocialClient;
import com.bss.campaign.exception.BadRequestException;
import com.bss.campaign.security.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AUDIENCE ACTIVATION: push an insight segment to the tenant's own
 * social platform as a Custom Audience (hashed emails, Meta Marketing
 * API wire shape) so the growth team retargets THEIR OWN customers with
 * first-party truth. Members without an email are counted out honestly;
 * a tenant without a platform configured is told so — the seam is
 * per-tenant, like everything else.
 */
@Service
public class AudienceSyncService {

    private static final Logger log = LoggerFactory.getLogger(AudienceSyncService.class);

    private final InsightClient insight;
    private final SocialClient social;
    private final TenantScope tenantScope;

    public AudienceSyncService(InsightClient insight, SocialClient social, TenantScope tenantScope) {
        this.insight = insight;
        this.social = social;
        this.tenantScope = tenantScope;
    }

    public Map<String, Object> sync(Map<String, Object> dto) {
        String segment = dto.get("segmentName") == null ? null : String.valueOf(dto.get("segmentName"));
        String audienceId = dto.get("audienceId") == null ? null : String.valueOf(dto.get("audienceId"));
        if (segment == null || segment.isBlank() || audienceId == null || audienceId.isBlank()) {
            throw new BadRequestException("segmentName and audienceId are required");
        }
        String tenant = tenantScope.currentTenantId();
        if (!social.configured(tenant)) {
            throw new BadRequestException(
                    "no social platform is configured for this tenant — the seam is per-tenant");
        }
        List<Map<String, Object>> members = insight.segmentMembers(segment);
        List<String> emails = members.stream()
                .map(m -> social.emailOf(String.valueOf(m.get("partyId"))))
                .filter(java.util.Objects::nonNull)
                .toList();
        int pushed = emails.isEmpty() ? 0 : social.pushAudience(tenant, audienceId, emails);
        log.info("segment '{}' pushed to social audience '{}': {} members, {} with email, {} accepted",
                segment, audienceId, members.size(), emails.size(), pushed);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("segment", segment);
        result.put("audienceId", audienceId);
        result.put("members", members.size());
        result.put("withEmail", emails.size());
        result.put("pushed", pushed);
        result.put("schema", "EMAIL_SHA256");
        return result;
    }
}
