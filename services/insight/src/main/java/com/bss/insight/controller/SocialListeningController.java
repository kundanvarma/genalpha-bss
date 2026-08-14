package com.bss.insight.controller;

import com.bss.insight.api.ApiConstants;
import com.bss.insight.service.SocialListeningService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Social listening: pull brand mentions, read the sentiment summary + feed. */
@RestController
@RequestMapping(ApiConstants.BASE_PATH + "/listening")
public class SocialListeningController {

    private final SocialListeningService service;

    public SocialListeningController(SocialListeningService service) {
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

    @GetMapping("/mentions")
    public ResponseEntity<List<Map<String, Object>>> mentions() {
        return ResponseEntity.ok(service.recent());
    }
}
