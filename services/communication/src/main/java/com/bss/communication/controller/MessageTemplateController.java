package com.bss.communication.controller;

import com.bss.communication.api.ApiConstants;
import com.bss.communication.dto.RenderPreviewRequest;
import com.bss.communication.dto.TemplateRequest;
import com.bss.communication.dto.TemplateView;
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
    public ResponseEntity<TemplateView> create(@RequestBody TemplateRequest dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(dto));
    }

    @GetMapping
    public ResponseEntity<List<TemplateView>> list() {
        return ResponseEntity.ok(service.list());
    }

    @GetMapping("/{id}")
    public ResponseEntity<TemplateView> get(@PathVariable String id) {
        return ResponseEntity.ok(service.get(id));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<TemplateView> patch(@PathVariable String id,
            @RequestBody TemplateRequest patch) {
        return ResponseEntity.ok(service.patch(id, patch));
    }

    /** Preview the copy this template renders for a locale + context. */
    @PostMapping("/{id}/render")
    public ResponseEntity<Map<String, String>> render(@PathVariable String id,
            @RequestBody RenderPreviewRequest body) {
        return ResponseEntity.ok(service.renderPreview(id, body.locale(),
                body.context() == null ? Map.of() : body.context()));
    }
}
