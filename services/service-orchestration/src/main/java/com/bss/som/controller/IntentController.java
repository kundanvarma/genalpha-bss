package com.bss.som.controller;

import com.bss.som.dto.IntentDtos.IntentRequest;
import com.bss.som.dto.IntentDtos.IntentView;
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

/** TMF921-shaped intent management: the front door of the autonomous OSS. */
@RestController
@RequestMapping("/tmf-api/intentManagement/v4")
public class IntentController {

    private final IntentService service;

    public IntentController(IntentService service) {
        this.service = service;
    }

    @PostMapping("/intent")
    public ResponseEntity<IntentView> create(@RequestBody IntentRequest dto) {
        IntentView created = service.create(dto);
        return ResponseEntity.created(URI.create(created.href())).body(created);
    }

    @GetMapping("/intent")
    public ResponseEntity<List<IntentView>> list() {
        return ResponseEntity.ok(service.findAll());
    }

    @GetMapping("/intent/{id}")
    public ResponseEntity<IntentView> byId(@PathVariable String id) {
        return ResponseEntity.ok(service.findById(id));
    }
}
