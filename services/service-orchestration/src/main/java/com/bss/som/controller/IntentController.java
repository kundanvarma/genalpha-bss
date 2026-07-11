package com.bss.som.controller;

import com.bss.som.service.IntentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Map;

/** TMF921-shaped intent management: the front door of the autonomous OSS. */
@RestController
@RequestMapping("/tmf-api/intentManagement/v4")
public class IntentController {

    private final IntentService service;

    public IntentController(IntentService service) {
        this.service = service;
    }

    @PostMapping("/intent")
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> dto) {
        Map<String, Object> created = service.create(dto);
        return ResponseEntity.created(URI.create(String.valueOf(created.get("href")))).body(created);
    }

    @GetMapping("/intent")
    public ResponseEntity<List<Map<String, Object>>> list() {
        return ResponseEntity.ok(service.findAll());
    }

    @GetMapping("/intent/{id}")
    public ResponseEntity<Map<String, Object>> byId(@PathVariable String id) {
        return ResponseEntity.ok(service.findById(id));
    }
}
