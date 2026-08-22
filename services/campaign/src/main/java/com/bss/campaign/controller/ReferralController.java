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
        return ResponseEntity.ok(referrals.redeem(String.valueOf(body.get("code")),
                body.get("areaCode") == null ? null : String.valueOf(body.get("areaCode"))));
    }

    /** G3 — tie my code to my local club (Klubbdugnad). */
    @PostMapping("/myClub")
    public ResponseEntity<Map<String, Object>> myClub(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(referrals.linkClub(
                body.get("clubOrgId") == null ? null : String.valueOf(body.get("clubOrgId"))));
    }

    /** G3 — community goals: staff create/list; progress is PUBLIC (a score,
     *  never a person). */
    @PostMapping("/community")
    public ResponseEntity<Map<String, Object>> createGoal(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(referrals.createGoal(body));
    }

    @GetMapping("/community")
    public ResponseEntity<java.util.List<Map<String, Object>>> listGoals() {
        return ResponseEntity.ok(referrals.listGoals());
    }

    @GetMapping("/community/{id}/progress")
    public ResponseEntity<Map<String, Object>> progress(
            @org.springframework.web.bind.annotation.PathVariable("id") String id) {
        return ResponseEntity.ok(referrals.progress(id));
    }

    /** G3 — Klubbdugnad season tally (staff). */
    @GetMapping("/clubs")
    public ResponseEntity<java.util.List<Map<String, Object>>> clubs() {
        return ResponseEntity.ok(referrals.clubReport());
    }

    @GetMapping("/report")
    public ResponseEntity<Map<String, Object>> report() {
        return ResponseEntity.ok(referrals.report());
    }
}
