package com.bss.loyalty.controller;

import com.bss.loyalty.api.ApiConstants;
import com.bss.loyalty.service.LoyaltyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * TMF658-flavored surface:
 *   GET/POST /loyaltyProgram          the tenant's program (data, marketer-owned)
 *   POST /loyaltyProgramMember        opt-in enroll (self)
 *   GET  /loyaltyProgramMember/me     my balance
 *   GET  /loyaltyTransaction          my journal
 *   POST /redeem                      burn — {type:"data", gb:N}
 *   GET  /liability                   the operator's outstanding-points number
 */
@RestController
@RequestMapping(ApiConstants.BASE_PATH)
public class LoyaltyController {

    private final LoyaltyService service;

    public LoyaltyController(LoyaltyService service) {
        this.service = service;
    }

    @GetMapping("/loyaltyProgram")
    public Map<String, Object> program() {
        return service.program();
    }

    @PostMapping("/loyaltyProgram")
    public Map<String, Object> upsertProgram(@RequestBody Map<String, Object> dto) {
        return service.upsertProgram(dto);
    }

    @PostMapping("/loyaltyProgramMember")
    public Map<String, Object> enroll() {
        return service.enroll();
    }

    @GetMapping("/loyaltyProgramMember/me")
    public Map<String, Object> me() {
        return service.me();
    }

    @GetMapping("/loyaltyTransaction")
    public List<Map<String, Object>> journal() {
        return service.myJournal();
    }

    @PostMapping("/redeem")
    public Map<String, Object> redeem(@RequestBody Map<String, Object> body) {
        String type = String.valueOf(body.get("type"));
        if ("data".equals(type)) {
            return service.redeemData(Integer.parseInt(String.valueOf(body.getOrDefault("gb", "1"))));
        }
        if ("voucher".equals(type)) {
            return service.redeemVoucher();
        }
        throw new com.bss.loyalty.exception.BadRequestException(
                "redeem types: {type:\"data\", gb:N} or {type:\"voucher\"}");
    }

    /** Machine/staff read — billing enriches the pricing context with this. */
    @GetMapping("/tier")
    public Map<String, Object> tier(
            @org.springframework.web.bind.annotation.RequestParam("partyId") String partyId) {
        return service.tierOf(partyId);
    }

    /** Operator goodwill / service-recovery credit — cause required. */
    @PostMapping("/adjust")
    public Map<String, Object> adjust(@RequestBody Map<String, Object> body) {
        return service.adjust(body);
    }

    /** On-demand expiry sweep — demos and suites don't wait for the clock. */
    @PostMapping("/expirySweep")
    public Map<String, Object> expirySweep() {
        return service.expirySweep();
    }

    @GetMapping("/liability")
    public Map<String, Object> liability() {
        return service.liability();
    }
}
