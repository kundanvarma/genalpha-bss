package com.bss.promotion.controller;

import com.bss.promotion.api.ApiConstants;
import com.bss.promotion.api.PagedResult;
import com.bss.promotion.service.PromotionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
public class PromotionController {

    private final PromotionService service;

    public PromotionController(PromotionService service) {
        this.service = service;
    }

    @PostMapping(ApiConstants.BASE_PATH + "/promotion")
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> dto) {
        Map<String, Object> created = service.create(dto);
        return ResponseEntity.created(URI.create(String.valueOf(created.get("href")))).body(created);
    }

    @GetMapping(ApiConstants.BASE_PATH + "/promotion")
    public ResponseEntity<List<Map<String, Object>>> list(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam Map<String, String> params) {
        Map<String, String> filters = new HashMap<>(params);
        filters.remove("offset");
        filters.remove("limit");
        PagedResult<Map<String, Object>> result = service.findAll(offset, limit, filters);
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(result.totalCount()))
                .body(result.items());
    }

    @GetMapping(ApiConstants.BASE_PATH + "/promotion/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
        return ResponseEntity.ok(service.findById(id));
    }

    @PatchMapping(ApiConstants.BASE_PATH + "/promotion/{id}")
    public ResponseEntity<Map<String, Object>> patch(@PathVariable String id,
            @RequestBody Map<String, Object> patch) {
        return ResponseEntity.ok(service.patch(id, patch));
    }

    /** Anonymous shop-window validation; never enumerates promotions. */
    @PostMapping(ApiConstants.BASE_PATH + "/checkPromotion")
    public ResponseEntity<Map<String, Object>> validate(@RequestBody Map<String, Object> request) {
        return ResponseEntity.ok(service.validate(request));
    }

    /** Machine seam: order completion redeems a code for its owner. */
    @PostMapping(ApiConstants.BASE_PATH + "/redemption")
    public ResponseEntity<Map<String, Object>> redeem(@RequestBody Map<String, Object> request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.redeem(request));
    }

    /** Billing's read: a customer's earned discounts. */
    @GetMapping(ApiConstants.BASE_PATH + "/redemption")
    public ResponseEntity<List<Map<String, Object>>> redemptions(
            @RequestParam("relatedPartyId") String relatedPartyId) {
        return ResponseEntity.ok(service.redemptionsFor(relatedPartyId));
    }
}
