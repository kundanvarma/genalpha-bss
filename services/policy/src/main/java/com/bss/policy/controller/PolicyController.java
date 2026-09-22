package com.bss.policy.controller;

import com.bss.policy.api.ApiConstants;
import com.bss.policy.api.PagedResult;
import com.bss.policy.dto.DecisionRequest;
import com.bss.policy.dto.DecisionView;
import com.bss.policy.dto.ExperienceView;
import com.bss.policy.dto.PolicyRulePatch;
import com.bss.policy.dto.PolicyRuleRequest;
import com.bss.policy.dto.PolicyRuleView;
import com.bss.policy.dto.PriceResult;
import com.bss.policy.dto.Teaser;
import com.bss.policy.service.PolicyService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(ApiConstants.BASE_PATH)
public class PolicyController {

    private static final TypeReference<Map<String, Object>> CONTEXT = new TypeReference<>() {
    };

    private final PolicyService service;
    private final ObjectMapper objectMapper;

    public PolicyController(PolicyService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/policyRule")
    public ResponseEntity<List<PolicyRuleView>> list(
            @RequestParam(defaultValue = "0") long offset,
            @RequestParam(defaultValue = "20") int limit) {
        PagedResult<PolicyRuleView> page = service.list(offset, limit);
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(page.totalCount()))
                .body(page.items());
    }

    @GetMapping("/policyRule/{id}")
    public PolicyRuleView get(@PathVariable String id) {
        return service.get(id);
    }

    @PostMapping("/policyRule")
    public ResponseEntity<PolicyRuleView> create(@RequestBody PolicyRuleRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(body));
    }

    @PatchMapping("/policyRule/{id}")
    public PolicyRuleView patch(@PathVariable String id, @RequestBody PolicyRulePatch body) {
        return service.patch(id, body);
    }

    @DeleteMapping("/policyRule/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** The personalization decision: the insight component asks, the
     * operator's experience rules (data, not code) answer. */
    @PostMapping("/personalization/experience")
    public ExperienceView experience(@RequestBody DecisionRequest body) {
        return service.experienceFor(context(body.context()));
    }

    /**
     * Decision endpoint the order pipeline calls: given a domain and a request
     * context, allow or deny. Returns 200 always (the decision is in the body);
     * a deny carries the rule id/name and the customer-facing message.
     */
    @PostMapping("/evaluate")
    public DecisionView evaluate(@RequestBody DecisionRequest body) {
        String domain = body.domain() == null ? "order" : body.domain();
        return service.evaluate(domain, context(body.context())).view();
    }

    /** The anonymous shop window for rules: what deals mention this offering. */
    @GetMapping("/price/teaser")
    public List<Teaser> teasers(@RequestParam String offeringId) {
        return service.teasers(offeringId);
    }

    /**
     * Anonymous indicative pricing: public deals only, labelled as such. The
     * body is the pricing context itself, or {@code {context: …}} — an open
     * document either way, so it arrives as a tree.
     */
    @PostMapping("/price/indicative")
    public PriceResult indicative(@RequestBody JsonNode body) {
        JsonNode context = body.path("context").isObject() ? body.get("context") : body;
        return service.indicative(context(context));
    }

    /**
     * Dynamic pricing: given a base subtotal and a pricing context, apply the
     * enabled pricing rules and return the adjustments plus the adjusted total.
     * Called at cart/quote/bill time — the price reflects rules authored as data.
     */
    @PostMapping("/price")
    public PriceResult price(@RequestBody DecisionRequest body) {
        return service.price(context(body.context()));
    }

    /** The JSON-logic context is the caller's open document; anything but an object counts as empty. */
    private Map<String, Object> context(JsonNode node) {
        return node != null && node.isObject() ? objectMapper.convertValue(node, CONTEXT) : Map.of();
    }
}
