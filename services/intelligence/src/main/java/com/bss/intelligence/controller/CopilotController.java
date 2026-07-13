package com.bss.intelligence.controller;

import com.bss.intelligence.api.ApiConstants;
import com.bss.intelligence.service.CopilotService;
import com.bss.intelligence.service.ProductCopilotService;
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
    private final ProductCopilotService productCopilot;

    public CopilotController(CopilotService service, ProductCopilotService productCopilot) {
        this.service = service;
        this.productCopilot = productCopilot;
    }

    @PostMapping("/customerSummary")
    public ResponseEntity<Map<String, Object>> summarize(@RequestBody Map<String, Object> request) {
        return ResponseEntity.ok(service.summarizeCustomer(request));
    }

    /** Chat-to-create: the product owner talks, the copilot proposes, the
     * console applies on confirmation — the model never writes. */
    @PostMapping("/productCopilot")
    public ResponseEntity<Map<String, Object>> productCopilot(@RequestBody Map<String, Object> request) {
        return ResponseEntity.ok(productCopilot.chat(request));
    }

    @PostMapping("/intentDraft")
    public ResponseEntity<Map<String, Object>> intentDraft(@RequestBody Map<String, Object> request) {
        return ResponseEntity.ok(service.draftIntent(request));
    }

    @PostMapping("/quoteNarrative")
    public ResponseEntity<Map<String, Object>> narrative(@RequestBody Map<String, Object> request) {
        return ResponseEntity.ok(service.draftQuoteNarrative(request));
    }

    @PostMapping("/ticketReply")
    public ResponseEntity<Map<String, Object>> reply(@RequestBody Map<String, Object> request) {
        return ResponseEntity.ok(service.draftTicketReply(request));
    }
}
