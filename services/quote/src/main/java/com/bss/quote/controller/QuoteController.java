package com.bss.quote.controller;

import com.bss.quote.api.ApiConstants;
import com.bss.quote.service.QuoteService;
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
import java.util.Map;

@RestController
@RequestMapping(ApiConstants.BASE_PATH)
public class QuoteController {

    private final QuoteService service;

    public QuoteController(QuoteService service) {
        this.service = service;
    }

    @PostMapping("/quote")
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> dto) {
        Map<String, Object> created = service.createFromIntent(dto);
        return ResponseEntity.created(URI.create(String.valueOf(created.get("href")))).body(created);
    }

    @GetMapping("/quote")
    public ResponseEntity<List<Map<String, Object>>> list() {
        return ResponseEntity.ok(service.findAll());
    }

    @GetMapping("/quote/{id}")
    public ResponseEntity<Map<String, Object>> byId(@PathVariable String id) {
        return ResponseEntity.ok(service.findById(id));
    }

    /** A branded, printable quote document (HTML) the rep can send. */
    @GetMapping(value = "/quote/{id}/document", produces = "text/html")
    public ResponseEntity<String> document(@PathVariable String id) {
        return ResponseEntity.ok(service.renderDocument(id));
    }

    @PatchMapping("/quote/{id}")
    public ResponseEntity<Map<String, Object>> patch(@PathVariable String id,
            @RequestBody Map<String, Object> patch) {
        return ResponseEntity.ok(service.patch(id, patch));
    }

    @PostMapping("/quote/{id}/accept")
    public ResponseEntity<Map<String, Object>> accept(@PathVariable String id) {
        return ResponseEntity.ok(service.accept(id));
    }

    /** Approve a pending discount (the human gate) so the quote can advance. */
    @PostMapping("/quote/{id}/approveDiscount")
    public ResponseEntity<Map<String, Object>> approveDiscount(@PathVariable String id) {
        return ResponseEntity.ok(service.approveDiscount(id));
    }

    // ---- CPQ configuration rules ----

    @PostMapping("/quote/configRule")
    public ResponseEntity<Map<String, Object>> createRule(@RequestBody Map<String, Object> dto) {
        return ResponseEntity.ok(service.createRule(dto));
    }

    @GetMapping("/quote/configRule")
    public ResponseEntity<List<Map<String, Object>>> listRules() {
        return ResponseEntity.ok(service.listRules());
    }

    /** The CPQ decision endpoint: check line items against the rules (no
     *  mutation) — agent-callable before committing a configuration. */
    @PostMapping("/quote/validate")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> validate(@RequestBody Map<String, Object> body) {
        Object items = body.get("items");
        List<Map<String, Object>> lineItems = items instanceof List<?>
                ? (List<Map<String, Object>>) items : List.of();
        return ResponseEntity.ok(service.validate(lineItems));
    }
}
