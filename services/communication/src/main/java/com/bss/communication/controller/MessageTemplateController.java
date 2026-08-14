package com.bss.communication.controller;

import com.bss.communication.api.ApiConstants;
import com.bss.communication.service.MessageTemplateService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Authoring + preview for reusable, localized message templates. */
@RestController
@RequestMapping(ApiConstants.BASE_PATH + "/messageTemplate")
public class MessageTemplateController {

    private final MessageTemplateService service;

    public MessageTemplateController(MessageTemplateService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(dto));
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list() {
        return ResponseEntity.ok(service.list());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
        return ResponseEntity.ok(service.get(id));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<Map<String, Object>> patch(@PathVariable String id,
            @RequestBody Map<String, Object> patch) {
        return ResponseEntity.ok(service.patch(id, patch));
    }

    /** Preview the copy this template renders for a locale + context. */
    @PostMapping("/{id}/render")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, String>> render(@PathVariable String id,
            @RequestBody Map<String, Object> body) {
        String locale = body.get("locale") == null ? null : String.valueOf(body.get("locale"));
        Map<String, Object> context = body.get("context") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        return ResponseEntity.ok(service.renderPreview(id, locale, context));
    }
}
