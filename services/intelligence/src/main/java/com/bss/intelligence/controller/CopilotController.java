package com.bss.intelligence.controller;

import com.bss.intelligence.api.ApiConstants;
import com.bss.intelligence.service.CopilotService;
import com.bss.intelligence.service.KnowledgeAskService;
import com.bss.intelligence.service.ProductCopilotService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
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
    private final KnowledgeAskService knowledgeAsk;

    public CopilotController(CopilotService service, ProductCopilotService productCopilot,
            KnowledgeAskService knowledgeAsk) {
        this.service = service;
        this.productCopilot = productCopilot;
        this.knowledgeAsk = knowledgeAsk;
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

    /** Ask the knowledge base: retrieval with the ASKER's own token (their
     * audience, their answer), then a grounded synthesis with sources. */
    @PostMapping("/knowledgeAsk")
    public ResponseEntity<Map<String, Object>> knowledgeAsk(@RequestBody Map<String, Object> request) {
        String question = String.valueOf(request.getOrDefault("question", ""));
        var auth = SecurityContextHolder.getContext().getAuthentication();
        String bearer = auth instanceof JwtAuthenticationToken jwt ? jwt.getToken().getTokenValue() : "";
        return ResponseEntity.ok(knowledgeAsk.ask(bearer, question));
    }
}
