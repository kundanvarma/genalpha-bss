package com.bss.quote.controller;

import com.bss.quote.api.ApiConstants;
import com.bss.quote.dto.ConfigRuleView;
import com.bss.quote.dto.ConfigurationCheck;
import com.bss.quote.dto.GuidedSelling;
import com.bss.quote.dto.PricingRuleView;
import com.bss.quote.dto.QuoteRequests.ConfigRuleRequest;
import com.bss.quote.dto.QuoteRequests.GuidedQuestionRequest;
import com.bss.quote.dto.QuoteRequests.GuidedRecommendationRequest;
import com.bss.quote.dto.QuoteRequests.PricingRuleRequest;
import com.bss.quote.dto.QuoteRequests.QuotePatch;
import com.bss.quote.dto.QuoteRequests.QuoteRequest;
import com.bss.quote.dto.QuoteRequests.SignRequest;
import com.bss.quote.dto.QuoteRequests.ValidateRequest;
import com.bss.quote.dto.QuoteView;
import com.bss.quote.service.QuoteService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping(ApiConstants.BASE_PATH)
public class QuoteController {

    private final QuoteService service;

    public QuoteController(QuoteService service) {
        this.service = service;
    }

    @PostMapping("/quote")
    public ResponseEntity<QuoteView> create(@RequestBody QuoteRequest dto) {
        QuoteView created = service.createFromIntent(dto);
        return ResponseEntity.created(URI.create(created.href())).body(created);
    }

    @GetMapping("/quote")
    public ResponseEntity<List<QuoteView>> list() {
        return ResponseEntity.ok(service.findAll());
    }

    @GetMapping("/quote/{id}")
    public ResponseEntity<QuoteView> byId(@PathVariable String id) {
        return ResponseEntity.ok(service.findById(id));
    }

    /** A branded, printable quote document (HTML) the rep can send. */
    @GetMapping(value = "/quote/{id}/document", produces = "text/html")
    public ResponseEntity<String> document(@PathVariable String id) {
        return ResponseEntity.ok(service.renderDocument(id));
    }

    @PatchMapping("/quote/{id}")
    public ResponseEntity<QuoteView> patch(@PathVariable String id, @RequestBody QuotePatch patch) {
        return ResponseEntity.ok(service.patch(id, patch));
    }

    @PostMapping("/quote/{id}/accept")
    public ResponseEntity<QuoteView> accept(@PathVariable String id) {
        return ResponseEntity.ok(service.accept(id));
    }

    /** Approve a pending discount (the human gate) so the quote can advance. */
    @PostMapping("/quote/{id}/approveDiscount")
    public ResponseEntity<QuoteView> approveDiscount(@PathVariable String id) {
        return ResponseEntity.ok(service.approveDiscount(id));
    }

    // ---- CPQ configuration rules ----

    @PostMapping("/quote/configRule")
    public ResponseEntity<ConfigRuleView> createRule(@RequestBody ConfigRuleRequest dto) {
        return ResponseEntity.ok(service.createRule(dto));
    }

    @GetMapping("/quote/configRule")
    public ResponseEntity<List<ConfigRuleView>> listRules() {
        return ResponseEntity.ok(service.listRules());
    }

    /** The CPQ decision endpoint: check line items against the rules (no
     *  mutation) — agent-callable before committing a configuration. */
    @PostMapping("/quote/validate")
    public ResponseEntity<ConfigurationCheck> validate(@RequestBody ValidateRequest body) {
        return ResponseEntity.ok(service.validate(body.items()));
    }

    // ---- CPQ guided selling ----

    @PostMapping("/quote/guidedQuestion")
    public ResponseEntity<GuidedSelling.QuestionView> createGuidedQuestion(
            @RequestBody GuidedQuestionRequest dto) {
        return ResponseEntity.ok(service.createGuidedQuestion(dto));
    }

    @GetMapping("/quote/guidedQuestion")
    public ResponseEntity<List<GuidedSelling.QuestionView>> guidedQuestions() {
        return ResponseEntity.ok(service.listGuidedQuestions());
    }

    @PostMapping("/quote/guidedRecommendation")
    public ResponseEntity<GuidedSelling.RecommendationRuleView> createGuidedRecommendation(
            @RequestBody GuidedRecommendationRequest dto) {
        return ResponseEntity.ok(service.createGuidedRecommendation(dto));
    }

    @GetMapping("/quote/guidedRecommendation")
    public ResponseEntity<List<GuidedSelling.RecommendationRuleView>> guidedRecommendations() {
        return ResponseEntity.ok(service.listGuidedRecommendations());
    }

    /** Guided-selling decision: answers → recommended offerings (agent-callable).
     *  The body is the questionnaire's answers — an open document keyed by
     *  question, flat or under {@code answers}. */
    @PostMapping("/quote/guidedRecommend")
    public ResponseEntity<GuidedSelling.Recommendations> guidedRecommend(@RequestBody JsonNode answers) {
        return ResponseEntity.ok(service.recommend(answers));
    }

    // ---- CPQ volume pricing rules ----

    @PostMapping("/quote/pricingRule")
    public ResponseEntity<PricingRuleView> createPricingRule(@RequestBody PricingRuleRequest dto) {
        return ResponseEntity.ok(service.createPricingRule(dto));
    }

    @GetMapping("/quote/pricingRule")
    public ResponseEntity<List<PricingRuleView>> pricingRules() {
        return ResponseEntity.ok(service.listPricingRules());
    }

    /** E-sign the quote document (the customer accepted it). */
    @PostMapping("/quote/{id}/sign")
    public ResponseEntity<QuoteView> sign(@PathVariable String id, @RequestBody SignRequest dto) {
        return ResponseEntity.ok(service.sign(id, dto));
    }
}
