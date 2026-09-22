package com.bss.intelligence.controller;

import com.bss.intelligence.api.ApiConstants;
import com.bss.intelligence.service.CopilotRequests.JourneyBrief;
import com.bss.intelligence.service.JourneyDraft;
import com.bss.intelligence.service.JourneyDraftService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** AI-native journey authoring: a NL brief in, a reviewable journey draft out. */
@RestController
@RequestMapping(ApiConstants.BASE_PATH)
public class JourneyDraftController {

    private final JourneyDraftService service;

    public JourneyDraftController(JourneyDraftService service) {
        this.service = service;
    }

    @PostMapping("/journeyDraft")
    public ResponseEntity<JourneyDraft> draft(@RequestBody JourneyBrief request) {
        return ResponseEntity.ok(service.draftJourney(request));
    }
}
