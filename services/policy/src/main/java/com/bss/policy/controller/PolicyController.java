package com.bss.policy.controller;

import com.bss.policy.api.ApiConstants;
import com.bss.policy.api.PagedResult;
import com.bss.policy.service.PolicyService;
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

    private final PolicyService service;

    public PolicyController(PolicyService service) {
        this.service = service;
    }

    @GetMapping("/policyRule")
    public ResponseEntity<List<Map<String, Object>>> list(
            @RequestParam(defaultValue = "0") long offset,
            @RequestParam(defaultValue = "20") int limit) {
        PagedResult<Map<String, Object>> page = service.list(offset, limit);
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(page.totalCount()))
                .body(page.items());
    }

    @GetMapping("/policyRule/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return service.get(id);
    }

    @PostMapping("/policyRule")
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(body));
    }

    @PatchMapping("/policyRule/{id}")
    public Map<String, Object> patch(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return service.patch(id, body);
    }

    @DeleteMapping("/policyRule/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Decision endpoint the order pipeline calls: given a domain and a request
     * context, allow or deny. Returns 200 always (the decision is in the body);
     * a deny carries the rule id/name and the customer-facing message.
     */
    @PostMapping("/evaluate")
    @SuppressWarnings("unchecked")
    public Map<String, Object> evaluate(@RequestBody Map<String, Object> body) {
        String domain = body.get("domain") == null ? "order" : String.valueOf(body.get("domain"));
        Object ctx = body.get("context");
        Map<String, Object> context = ctx instanceof Map ? (Map<String, Object>) ctx : Map.of();
        return service.evaluate(domain, context).toMap();
    }
}
