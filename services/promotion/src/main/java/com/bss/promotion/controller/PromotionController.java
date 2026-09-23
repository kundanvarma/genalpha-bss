package com.bss.promotion.controller;

import com.bss.promotion.api.ApiConstants;
import com.bss.promotion.api.PagedResult;
import com.bss.promotion.dto.CheckPromotionRequest;
import com.bss.promotion.dto.PromotionCheck;
import com.bss.promotion.dto.PromotionPatch;
import com.bss.promotion.dto.PromotionRedemptionView;
import com.bss.promotion.dto.PromotionRequest;
import com.bss.promotion.dto.PromotionView;
import com.bss.promotion.dto.RedeemRequest;
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
    public ResponseEntity<PromotionView> create(@RequestBody PromotionRequest dto) {
        PromotionView created = service.create(dto);
        return ResponseEntity.created(URI.create(created.href())).body(created);
    }

    @GetMapping(ApiConstants.BASE_PATH + "/promotion")
    public ResponseEntity<List<PromotionView>> list(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam Map<String, String> params) {
        Map<String, String> filters = new HashMap<>(params);
        filters.remove("offset");
        filters.remove("limit");
        PagedResult<PromotionView> result = service.findAll(offset, limit, filters);
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(result.totalCount()))
                .body(result.items());
    }

    @GetMapping(ApiConstants.BASE_PATH + "/promotion/{id}")
    public ResponseEntity<PromotionView> get(@PathVariable String id) {
        return ResponseEntity.ok(service.findById(id));
    }

    @PatchMapping(ApiConstants.BASE_PATH + "/promotion/{id}")
    public ResponseEntity<PromotionView> patch(@PathVariable String id,
            @RequestBody PromotionPatch patch) {
        return ResponseEntity.ok(service.patch(id, patch));
    }

    /** Anonymous shop-window validation; never enumerates promotions. */
    @PostMapping(ApiConstants.BASE_PATH + "/checkPromotion")
    public ResponseEntity<PromotionCheck> validate(@RequestBody CheckPromotionRequest request) {
        return ResponseEntity.ok(service.validate(request));
    }

    /** Machine seam: order completion redeems a code for its owner. */
    @PostMapping(ApiConstants.BASE_PATH + "/redemption")
    public ResponseEntity<PromotionRedemptionView> redeem(@RequestBody RedeemRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.redeem(request));
    }

    /** Billing's read: a customer's earned discounts. */
    @GetMapping(ApiConstants.BASE_PATH + "/redemption")
    public ResponseEntity<List<PromotionRedemptionView>> redemptions(
            @RequestParam("relatedPartyId") String relatedPartyId) {
        return ResponseEntity.ok(service.redemptionsFor(relatedPartyId));
    }
}
