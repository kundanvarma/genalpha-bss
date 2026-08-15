package com.bss.insight.controller;

import com.bss.insight.api.ApiConstants;
import com.bss.insight.service.SocialCareService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Social care: pull inbound DMs, triage them, read the care queue + summary. */
@RestController
@RequestMapping(ApiConstants.BASE_PATH + "/care")
public class SocialCareController {

    private final SocialCareService service;

    public SocialCareController(SocialCareService service) {
        this.service = service;
    }

    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> sync() {
        return ResponseEntity.ok(service.sync());
    }

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary() {
        return ResponseEntity.ok(service.summary());
    }

    @GetMapping("/queue")
    public ResponseEntity<List<Map<String, Object>>> queue() {
        return ResponseEntity.ok(service.queue());
    }
}
