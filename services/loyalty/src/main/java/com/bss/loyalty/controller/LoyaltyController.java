package com.bss.loyalty.controller;

import com.bss.loyalty.api.ApiConstants;
import com.bss.loyalty.dto.AdjustRequest;
import com.bss.loyalty.dto.LiabilityView;
import com.bss.loyalty.dto.LoyaltyMemberView;
import com.bss.loyalty.dto.LoyaltyProgramRequest;
import com.bss.loyalty.dto.LoyaltyProgramView;
import com.bss.loyalty.dto.LoyaltyTransactionView;
import com.bss.loyalty.dto.RedeemReceipt;
import com.bss.loyalty.dto.RedeemRequest;
import com.bss.loyalty.dto.SweepReceipt;
import com.bss.loyalty.dto.TierVerdict;
import com.bss.loyalty.service.LoyaltyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
    public LoyaltyProgramView program() {
        return service.program();
    }

    @PostMapping("/loyaltyProgram")
    public LoyaltyProgramView upsertProgram(@RequestBody LoyaltyProgramRequest dto) {
        return service.upsertProgram(dto);
    }

    @PostMapping("/loyaltyProgramMember")
    public LoyaltyMemberView enroll() {
        return service.enroll();
    }

    @GetMapping("/loyaltyProgramMember/me")
    public LoyaltyMemberView me() {
        return service.me();
    }

    @GetMapping("/loyaltyTransaction")
    public List<LoyaltyTransactionView> journal() {
        return service.myJournal();
    }

    @PostMapping("/redeem")
    public RedeemReceipt redeem(@RequestBody RedeemRequest body) {
        if ("data".equals(body.type())) {
            return service.redeemData(body.gbOrDefault());
        }
        if ("voucher".equals(body.type())) {
            return service.redeemVoucher();
        }
        throw new com.bss.loyalty.exception.BadRequestException(
                "redeem types: {type:\"data\", gb:N} or {type:\"voucher\"}");
    }

    /** Machine/staff read — billing enriches the pricing context with this. */
    @GetMapping("/tier")
    public TierVerdict tier(@RequestParam("partyId") String partyId) {
        return service.tierOf(partyId);
    }

    /** Operator goodwill / service-recovery credit — cause required. */
    @PostMapping("/adjust")
    public LoyaltyMemberView adjust(@RequestBody AdjustRequest body) {
        return service.adjust(body);
    }

    /** On-demand expiry sweep — demos and suites don't wait for the clock. */
    @PostMapping("/expirySweep")
    public SweepReceipt expirySweep() {
        return service.expirySweep();
    }

    @GetMapping("/liability")
    public LiabilityView liability() {
        return service.liability();
    }
}
