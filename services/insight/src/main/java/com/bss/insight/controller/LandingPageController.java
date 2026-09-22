package com.bss.insight.controller;

import com.bss.insight.api.ApiConstants;
import com.bss.insight.dto.LandingPageRequest;
import com.bss.insight.dto.LandingPageView;
import com.bss.insight.dto.LeadCapture;
import com.bss.insight.dto.LeadForm;
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
    public ResponseEntity<LandingPageView> create(@RequestBody LandingPageRequest dto) {
        return ResponseEntity.ok(service.create(dto));
    }

    @GetMapping
    public ResponseEntity<List<LandingPageView>> list() {
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
    public ResponseEntity<LeadCapture> lead(@PathVariable String slug, @RequestBody LeadForm body) {
        return ResponseEntity.ok(service.captureLead(slug, body));
    }

    @org.springframework.web.bind.annotation.PatchMapping("/{id}")
    public ResponseEntity<LandingPageView> patch(@PathVariable String id, @RequestBody LandingPageRequest body) {
        return ResponseEntity.ok(service.patch(id, body));
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
