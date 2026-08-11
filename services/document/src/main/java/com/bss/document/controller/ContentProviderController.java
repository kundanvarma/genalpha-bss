package com.bss.document.controller;

import com.bss.document.api.ApiConstants;
import com.bss.document.service.ContentProviderConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Bind the request's tenant to an external headless CMS/DAM (or clear it back to
 * the hosted DAM). Back-office only (document:write); the token is supplied as a
 * secret-ref (env var name), never the value. One binding per tenant.
 */
@RestController
@RequestMapping(ApiConstants.BASE_PATH + "/contentProvider")
public class ContentProviderController {

    private final ContentProviderConfigService service;

    public ContentProviderController(ContentProviderConfigService service) {
        this.service = service;
    }

    @PutMapping
    public ResponseEntity<Map<String, Object>> upsert(@RequestBody Map<String, Object> dto) {
        return ResponseEntity.ok(service.upsert(dto));
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> current() {
        return ResponseEntity.ok(service.currentAsMap());
    }

    @DeleteMapping
    public ResponseEntity<Void> clear() {
        service.deleteForCurrentTenant();
        return ResponseEntity.noContent().build();
    }
}
