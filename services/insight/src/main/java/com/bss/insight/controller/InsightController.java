package com.bss.insight.controller;

import com.bss.insight.api.ApiConstants;
import com.bss.insight.dto.AnalyticsAudience;
import com.bss.insight.dto.ConsentReceipt;
import com.bss.insight.dto.Experience;
import com.bss.insight.dto.LeadSignal;
import com.bss.insight.dto.PartyProfile;
import com.bss.insight.dto.PartySegments;
import com.bss.insight.dto.ProfileRow;
import com.bss.insight.dto.SegmentMember;
import com.bss.insight.dto.StitchReceipt;
import com.bss.insight.dto.VisitorRequests;
import com.bss.insight.service.InsightService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping(ApiConstants.BASE_PATH)
public class InsightController {

    private final InsightService service;

    public InsightController(InsightService service) {
        this.service = service;
    }

    /** The consent choice — anonymous by nature; the gateway's hostname
     * mapping decides the tenant. */
    @PostMapping("/consent")
    public ResponseEntity<ConsentReceipt> consent(@RequestBody VisitorRequests.Consent dto) {
        return ResponseEntity.ok(service.consent(dto.visitorId(),
                Boolean.TRUE.equals(dto.analytics()), Boolean.TRUE.equals(dto.personalization())));
    }

    /** A behavioral breadcrumb; 204 regardless — consent state never leaks. */
    @PostMapping("/event")
    public ResponseEntity<Void> event(@RequestBody VisitorRequests.Event dto) {
        service.event(dto.visitorId(), dto.type(), dto.category(), dto.offeringId(), dto.utmSource());
        return ResponseEntity.noContent().build();
    }

    /** The login stitch: caller's verified token subject becomes the party. */
    @PostMapping("/stitch")
    public ResponseEntity<StitchReceipt> stitch(@RequestBody VisitorRequests.Stitch dto) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        String partyId = auth instanceof JwtAuthenticationToken jwt ? jwt.getName() : null;
        return ResponseEntity.ok(service.stitch(dto.visitorId(), partyId));
    }

    /** "What should this person see?" */
    @GetMapping("/experience")
    public ResponseEntity<Experience> experience(@RequestParam String visitorId) {
        return ResponseEntity.ok(service.experience(visitorId));
    }

    /** Known customers in a segment (insight:read — the campaign engine). */
    @GetMapping("/segmentMembers")
    public ResponseEntity<List<SegmentMember>> segmentMembers(@RequestParam String segment) {
        return ResponseEntity.ok(service.segmentMembers(segment));
    }

    /** A known customer's merged interests (insight:read — machines). */
    @GetMapping("/partyProfile")
    public ResponseEntity<PartyProfile> partyProfile(@RequestParam String partyId) {
        return ResponseEntity.ok(service.partyProfile(partyId));
    }

    /** The lead signal for the sales funnel (insight:read — machines): is this
     *  email a known prospect, and has it engaged? */
    @GetMapping("/leadSignal")
    public ResponseEntity<LeadSignal> leadSignal(@RequestParam String email) {
        return ResponseEntity.ok(service.leadSignal(email));
    }

    /** A party's CDP segments (insight:read — machines): for CPQ segment pricing. */
    @GetMapping("/partySegments")
    public ResponseEntity<PartySegments> partySegments(@RequestParam String partyId) {
        return ResponseEntity.ok(service.partySegments(partyId));
    }

    /** The tenant's audience catalog from their OWN analytics, through the
     * GA4 Data API wire shape (insight:read). */
    @GetMapping("/audiences")
    public ResponseEntity<List<AnalyticsAudience>> audiences() {
        return ResponseEntity.ok(service.audienceCatalog());
    }

    /** Back-office (insight:read): one profile by id, or the paginated + searchable
     * consent ledger. Emits X-Total-Count so the console pager works at scale. */
    @GetMapping("/profile")
    public ResponseEntity<?> profile(@RequestParam(required = false) String visitorId,
            @RequestParam(defaultValue = "0") long offset,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) String q) {
        if (visitorId != null && !visitorId.isBlank()) {
            return ResponseEntity.ok(service.profileOf(visitorId));
        }
        com.bss.insight.api.PagedResult<ProfileRow> page =
                service.profilePage(offset, Math.min(Math.max(limit, 1), 200), q);
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(page.totalCount()))
                .body(page.items());
    }
}
