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

    @PatchMapping("/quote/{id}")
    public ResponseEntity<Map<String, Object>> patch(@PathVariable String id,
            @RequestBody Map<String, Object> patch) {
        return ResponseEntity.ok(service.patch(id, patch));
    }

    @PostMapping("/quote/{id}/accept")
    public ResponseEntity<Map<String, Object>> accept(@PathVariable String id) {
        return ResponseEntity.ok(service.accept(id));
    }
}
