package com.bss.campaign.privacy;

import com.bss.campaign.repository.JourneyEnrollmentRepository;
import com.bss.campaign.repository.MarketingTouchRepository;
import com.bss.campaign.security.TenantScope;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * The GDPR corner of the campaign engine: a person's marketing shadow —
 * journey enrollments and every recorded touch. EXPORT rides the
 * caller's own token; ERASE (DPO only) removes the shadow entirely:
 * marketing history has no legal hold to hide behind.
 */
@RestController
@RequestMapping("/privacy/v1")
public class PrivacyController {

    private final JourneyEnrollmentRepository enrollments;
    private final MarketingTouchRepository touches;
    private final TenantScope tenantScope;

    public PrivacyController(JourneyEnrollmentRepository enrollments,
            MarketingTouchRepository touches, TenantScope tenantScope) {
        this.enrollments = enrollments;
        this.touches = touches;
        this.tenantScope = tenantScope;
    }

    @GetMapping("/export")
    public Map<String, Object> export(@RequestParam(required = false) String partyId) {
        String subject = subject();
        String target = partyId == null || partyId.isBlank() ? subject : partyId;
        if (!target.equals(subject) && !isDpo()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND); // 404, never 403
        }
        String tenant = tenantScope.currentTenantId();
        var enrolled = enrollments.findByTenantIdAndPartyId(tenant, target);
        var touched = touches.findByTenantIdAndPartyId(tenant, target);
        return Map.of("category", "marketing",
                "count", enrolled.size() + touched.size(),
                "items", Map.of("journeyEnrollments", enrolled, "marketingTouches", touched));
    }

    @PostMapping("/erase")
    public Map<String, Object> erase(@RequestBody Map<String, Object> request) {
        if (!isDpo()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        String target = String.valueOf(request.get("partyId"));
        String tenant = tenantScope.currentTenantId();
        var enrolled = enrollments.findByTenantIdAndPartyId(tenant, target);
        var touched = touches.findByTenantIdAndPartyId(tenant, target);
        enrollments.deleteAll(enrolled);
        touches.deleteAll(touched);
        return Map.of("category", "marketing",
                "deleted", enrolled.size() + touched.size(), "retained", 0);
    }

    private String subject() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "" : auth.getName();
    }

    private boolean isDpo() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        for (GrantedAuthority ga : auth.getAuthorities()) {
            if ("roles:admin".equals(ga.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
