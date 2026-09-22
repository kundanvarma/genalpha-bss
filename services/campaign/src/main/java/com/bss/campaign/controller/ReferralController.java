package com.bss.campaign.controller;

import com.bss.campaign.api.ApiConstants;
import com.bss.campaign.dto.ClubLinkReceipt;
import com.bss.campaign.dto.ClubLinkRequest;
import com.bss.campaign.dto.ClubTally;
import com.bss.campaign.dto.CommunityGoalRequest;
import com.bss.campaign.dto.CommunityGoalView;
import com.bss.campaign.dto.RedeemReceipt;
import com.bss.campaign.dto.RedeemRequest;
import com.bss.campaign.dto.ReferralCodeView;
import com.bss.campaign.dto.ReferralReport;
import com.bss.campaign.service.ReferralService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
    public ResponseEntity<ReferralCodeView> myCode() {
        return ResponseEntity.ok(referrals.myCode());
    }

    @PostMapping("/redeem")
    public ResponseEntity<RedeemReceipt> redeem(@RequestBody RedeemRequest body) {
        return ResponseEntity.ok(referrals.redeem(body.code(), body.areaCode()));
    }

    /** G3 — tie my code to my local club (Klubbdugnad). */
    @PostMapping("/myClub")
    public ResponseEntity<ClubLinkReceipt> myClub(@RequestBody ClubLinkRequest body) {
        return ResponseEntity.ok(referrals.linkClub(body.clubOrgId()));
    }

    /** G3 — community goals: staff create/list; progress is PUBLIC (a score,
     *  never a person). */
    @PostMapping("/community")
    public ResponseEntity<CommunityGoalView> createGoal(@RequestBody CommunityGoalRequest body) {
        return ResponseEntity.ok(referrals.createGoal(body));
    }

    @GetMapping("/community")
    public ResponseEntity<List<CommunityGoalView>> listGoals() {
        return ResponseEntity.ok(referrals.listGoals());
    }

    @GetMapping("/community/{id}/progress")
    public ResponseEntity<CommunityGoalView> progress(
            @org.springframework.web.bind.annotation.PathVariable("id") String id) {
        return ResponseEntity.ok(referrals.progress(id));
    }

    /** G3 — Klubbdugnad season tally (staff). */
    @GetMapping("/clubs")
    public ResponseEntity<List<ClubTally>> clubs() {
        return ResponseEntity.ok(referrals.clubReport());
    }

    @GetMapping("/report")
    public ResponseEntity<ReferralReport> report() {
        return ResponseEntity.ok(referrals.report());
    }
}
