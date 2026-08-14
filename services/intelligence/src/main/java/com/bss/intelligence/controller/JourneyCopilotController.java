package com.bss.intelligence.controller;

import com.bss.intelligence.api.ApiConstants;
import com.bss.intelligence.service.JourneyCopilotService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Conversational authoring for journeys and campaigns — chat in, proposal out. */
@RestController
@RequestMapping(ApiConstants.BASE_PATH)
public class JourneyCopilotController {

    private final JourneyCopilotService service;

    public JourneyCopilotController(JourneyCopilotService service) {
        this.service = service;
    }

    @PostMapping("/journeyCopilot")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, Object> request) {
        return ResponseEntity.ok(service.chat(request));
    }
}
