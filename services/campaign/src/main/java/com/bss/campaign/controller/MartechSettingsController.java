package com.bss.campaign.controller;

import com.bss.campaign.api.ApiConstants;
import com.bss.campaign.service.FrequencyGuard;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** The tenant's martech guardrails (frequency cap). GET returns a
 * one-row list (console-friendly); POST upserts the row. */
@RestController
@RequestMapping(ApiConstants.BASE_PATH + "/settings")
public class MartechSettingsController {

    private final FrequencyGuard guard;

    public MartechSettingsController(FrequencyGuard guard) {
        this.guard = guard;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> get() {
        return ResponseEntity.ok(List.of(guard.settingsOf()));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> save(@RequestBody Map<String, Object> dto) {
        return ResponseEntity.ok(guard.save(dto));
    }
}
