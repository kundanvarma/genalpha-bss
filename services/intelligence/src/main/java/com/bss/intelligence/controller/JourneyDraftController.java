package com.bss.intelligence.controller;

import com.bss.intelligence.api.ApiConstants;
import com.bss.intelligence.service.JourneyDraftService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** AI-native journey authoring: a NL brief in, a reviewable journey draft out. */
@RestController
@RequestMapping(ApiConstants.BASE_PATH)
public class JourneyDraftController {

    private final JourneyDraftService service;

    public JourneyDraftController(JourneyDraftService service) {
        this.service = service;
    }

    @PostMapping("/journeyDraft")
    public ResponseEntity<Map<String, Object>> draft(@RequestBody Map<String, Object> request) {
        return ResponseEntity.ok(service.draftJourney(request));
    }
}
