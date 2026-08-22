package com.bss.campaign.controller;

import com.bss.campaign.api.ApiConstants;
import com.bss.campaign.service.ReferralService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** The referral doors: a customer's own code and redemption (self-scoped),
 *  and the staff report. */
@RestController
@RequestMapping(ApiConstants.BASE_PATH + "/referral")
public class ReferralController {

    private final ReferralService referrals;

    public ReferralController(ReferralService referrals) {
        this.referrals = referrals;
    }

    @GetMapping("/myCode")
    public ResponseEntity<Map<String, Object>> myCode() {
        return ResponseEntity.ok(referrals.myCode());
    }

    @PostMapping("/redeem")
    public ResponseEntity<Map<String, Object>> redeem(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(referrals.redeem(String.valueOf(body.get("code"))));
    }

    @GetMapping("/report")
    public ResponseEntity<Map<String, Object>> report() {
        return ResponseEntity.ok(referrals.report());
    }
}
