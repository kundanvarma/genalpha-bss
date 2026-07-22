package com.bss.recommendation.controller;

import com.bss.recommendation.api.ApiConstants;
import com.bss.recommendation.service.AffinityRecommender;
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
    private final AffinityRecommender affinity;

    public RecommendationController(RecommendationService service, AffinityRecommender affinity) {
        this.service = service;
        this.affinity = affinity;
    }

    @GetMapping(ApiConstants.BASE_PATH + "/recommendation")
    public ResponseEntity<List<Map<String, Object>>> recommendation(
            @RequestParam(name = "relatedPartyId", required = false) String relatedPartyId) {
        return ResponseEntity.ok(List.of(service.recommendationFor(relatedPartyId)));
    }

    /** "Customers who bought this also bought" — public (product page),
     * aggregate only, min-support protected. */
    @GetMapping(ApiConstants.BASE_PATH + "/affinity")
    public ResponseEntity<List<Map<String, Object>>> affinity(
            @RequestParam(name = "forOfferingId") String forOfferingId) {
        return ResponseEntity.ok(affinity.alsoBought(forOfferingId));
    }
}
