package com.bss.recommendation.controller;

import com.bss.recommendation.api.ApiConstants;
import com.bss.recommendation.service.RecommendationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class RecommendationController {

    private final RecommendationService service;

    public RecommendationController(RecommendationService service) {
        this.service = service;
    }

    @GetMapping(ApiConstants.BASE_PATH + "/recommendation")
    public ResponseEntity<List<Map<String, Object>>> recommendation(
            @RequestParam(name = "relatedPartyId", required = false) String relatedPartyId) {
        return ResponseEntity.ok(List.of(service.recommendationFor(relatedPartyId)));
    }
}
