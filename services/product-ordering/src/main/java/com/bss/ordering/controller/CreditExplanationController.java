package com.bss.ordering.controller;

import com.bss.ordering.api.ApiConstants;
import com.bss.ordering.credit.CreditCheckNotice;
import com.bss.ordering.credit.CreditExplanation;
import com.bss.ordering.credit.CreditExplanationService;
import com.bss.ordering.credit.CreditReviewReceipt;
import com.bss.ordering.credit.CreditReviewRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The customer's face of the credit seam (#422) — the back office keeps
 * {@link CreditDecisionController}:
 * <ul>
 *   <li>GET  creditDecision/notice — the notice the shop shows before a credit-checked order;</li>
 *   <li>GET  productOrder/{id}/creditDecision — why, for the order's owner (404 for anyone else's);</li>
 *   <li>POST productOrder/{id}/creditDecision/review — ask a person to look again (201 filed, 200 already filed).</li>
 * </ul>
 */
@RestController
public class CreditExplanationController {

    private final CreditExplanationService service;

    public CreditExplanationController(CreditExplanationService service) {
        this.service = service;
    }

    @GetMapping(ApiConstants.BASE_PATH + "/creditDecision/notice")
    public CreditCheckNotice notice() {
        return service.notice();
    }

    @GetMapping(ApiConstants.BASE_PATH + "/productOrder/{id}/creditDecision")
    public CreditExplanation explain(@PathVariable("id") String id) {
        return service.explain(id);
    }

    @PostMapping(ApiConstants.BASE_PATH + "/productOrder/{id}/creditDecision/review")
    public ResponseEntity<CreditReviewReceipt> review(@PathVariable("id") String id,
            @RequestBody(required = false) CreditReviewRequest body) {
        CreditExplanationService.Result result = service.requestReview(id,
                body == null ? CreditReviewRequest.EMPTY : body);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.receipt());
    }
}
