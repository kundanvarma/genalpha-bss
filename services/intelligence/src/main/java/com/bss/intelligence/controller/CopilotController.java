package com.bss.intelligence.controller;

import com.bss.intelligence.api.ApiConstants;
import com.bss.intelligence.service.CopilotService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping(ApiConstants.BASE_PATH)
public class CopilotController {

    private final CopilotService service;

    public CopilotController(CopilotService service) {
        this.service = service;
    }

    @PostMapping("/customerSummary")
    public ResponseEntity<Map<String, Object>> summarize(@RequestBody Map<String, Object> request) {
        return ResponseEntity.ok(service.summarizeCustomer(request));
    }

    @PostMapping("/ticketReply")
    public ResponseEntity<Map<String, Object>> reply(@RequestBody Map<String, Object> request) {
        return ResponseEntity.ok(service.draftTicketReply(request));
    }
}
