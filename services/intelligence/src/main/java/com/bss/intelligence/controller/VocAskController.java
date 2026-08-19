package com.bss.intelligence.controller;

import com.bss.intelligence.api.ApiConstants;
import com.bss.intelligence.client.BssApiClient;
import com.bss.intelligence.llm.AiGovernor;
import com.bss.intelligence.llm.LlmAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * The ask half of SI-P5: a marketer asks the VoC data a question in plain
 * language. The model answers from the AGGREGATES ONLY (per-aspect stats,
 * battery pain points, alert rows — never raw signal text: the doctrine that
 * keeps the frontier tier away from customer words), grounded hard: what is
 * not in the data must be said to be unknown, and pain points are cited by
 * signalId so every claim has a receipt in the pane's drill-down.
 */
@RestController
@RequestMapping(ApiConstants.BASE_PATH + "/voc")
public class VocAskController {

    private static final String SYSTEM = """
            You answer a telecom marketer's question about Voice-of-Customer data. You get \
            AGGREGATES ONLY (per-aspect counts, sentiment mix, weekly trend, top pain points with \
            signalId, early-warning alerts). Answer in 2-5 sentences, concrete numbers first. \
            Cite pain points by [signalId] so the reader can open the receipt. If the data does \
            not answer the question, SAY SO — never invent a number or a topic.""";

    private final BssApiClient bss;
    private final AiGovernor governor;
    private final ObjectMapper objectMapper;

    public VocAskController(BssApiClient bss, AiGovernor governor, ObjectMapper objectMapper) {
        this.bss = bss;
        this.governor = governor;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/ask")
    public ResponseEntity<Map<String, Object>> ask(@RequestBody Map<String, Object> body) {
        String question = body.get("question") == null ? "" : String.valueOf(body.get("question")).trim();
        if (question.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question is required");
        }
        Map<String, Object> summary = bss.vocSummary();
        String data;
        try {
            data = objectMapper.writeValueAsString(summary);
        } catch (Exception e) {
            data = "{}";
        }
        String answer = governor.complete("voc-ask", LlmAdapter.Tier.SMART, SYSTEM,
                "VoC aggregates:\n" + data + "\n\nQuestion: " + question);
        return ResponseEntity.ok(Map.of(
                "question", question,
                "answer", answer == null ? "" : answer,
                "groundedOn", "voc-aggregates (never raw signal text)"));
    }
}
