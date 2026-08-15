package com.bss.insight.controller;

import com.bss.insight.api.ApiConstants;
import com.bss.insight.service.LandingPageService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Campaign landing pages. Authoring is back-office (insight:read); the page view
 * and the lead submit are PUBLIC (an anonymous visitor arriving from an ad/email)
 * — consent is enforced in the capture, not by a login.
 */
@RestController
@RequestMapping(ApiConstants.BASE_PATH + "/landing")
public class LandingPageController {

    private final LandingPageService service;

    public LandingPageController(LandingPageService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> dto) {
        return ResponseEntity.ok(service.create(dto));
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list() {
        return ResponseEntity.ok(service.list());
    }

    /** PUBLIC — the rendered landing page a campaign links to. */
    @GetMapping(value = "/{slug}/view", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> view(@PathVariable String slug,
            @RequestParam(name = "utm_source", required = false) String utmSource) {
        return ResponseEntity.ok(service.renderHtml(slug, utmSource));
    }

    /** PUBLIC — a consented form submit becomes a prospect stamped with the campaign. */
    @PostMapping("/{slug}/lead")
    public ResponseEntity<Map<String, Object>> lead(@PathVariable String slug,
            @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(service.captureLead(slug, body));
    }
}
