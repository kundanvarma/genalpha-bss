package com.bss.insight.controller;

import com.bss.insight.api.ApiConstants;
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

import java.util.Map;

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
    public ResponseEntity<Map<String, Object>> consent(@RequestBody Map<String, Object> dto) {
        return ResponseEntity.ok(service.consent(
                String.valueOf(dto.get("visitorId")),
                Boolean.TRUE.equals(dto.get("analytics")),
                Boolean.TRUE.equals(dto.get("personalization"))));
    }

    /** A behavioral breadcrumb; 204 regardless — consent state never leaks. */
    @PostMapping("/event")
    public ResponseEntity<Void> event(@RequestBody Map<String, Object> dto) {
        service.event(String.valueOf(dto.get("visitorId")),
                dto.get("type") == null ? null : String.valueOf(dto.get("type")),
                dto.get("category") == null ? null : String.valueOf(dto.get("category")),
                dto.get("offeringId") == null ? null : String.valueOf(dto.get("offeringId")),
                dto.get("utmSource") == null ? null : String.valueOf(dto.get("utmSource")));
        return ResponseEntity.noContent().build();
    }

    /** The login stitch: caller's verified token subject becomes the party. */
    @PostMapping("/stitch")
    public ResponseEntity<Map<String, Object>> stitch(@RequestBody Map<String, Object> dto) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        String partyId = auth instanceof JwtAuthenticationToken jwt ? jwt.getName() : null;
        return ResponseEntity.ok(service.stitch(String.valueOf(dto.get("visitorId")), partyId));
    }

    /** "What should this person see?" */
    @GetMapping("/experience")
    public ResponseEntity<Map<String, Object>> experience(@RequestParam String visitorId) {
        return ResponseEntity.ok(service.experience(visitorId));
    }

    /** Back-office window into one profile (insight:read). */
    @GetMapping("/profile")
    public ResponseEntity<Map<String, Object>> profile(@RequestParam String visitorId) {
        return ResponseEntity.ok(service.profileOf(visitorId));
    }
}
