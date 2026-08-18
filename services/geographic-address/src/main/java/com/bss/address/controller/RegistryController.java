package com.bss.address.controller;

import com.bss.address.api.ApiConstants;
import com.bss.address.service.RegistryConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The operator's national-registry bindings (per tenant, per country). GET
 * lists (address:read); PUT/DELETE edit (address:write). One binding per
 * (tenant, country); the credential is a secret-ref, never the value.
 */
@RestController
@RequestMapping(ApiConstants.BASE_PATH + "/registry")
public class RegistryController {

    private final RegistryConfigService service;

    public RegistryController(RegistryConfigService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list() {
        return ResponseEntity.ok(service.listForCurrentTenant());
    }

    @PutMapping
    public ResponseEntity<Map<String, Object>> upsert(@RequestBody Map<String, Object> dto) {
        return ResponseEntity.ok(service.upsert(dto));
    }

    @DeleteMapping("/{country}")
    public ResponseEntity<Void> delete(@PathVariable String country) {
        service.delete(country);
        return ResponseEntity.noContent().build();
    }

    /** Test connection — reachability of the configured base URL (GET /health,
     * short timeout); never a person lookup. */
    @PostMapping("/{country}/test")
    public ResponseEntity<Map<String, Object>> test(@PathVariable String country) {
        return ResponseEntity.ok(service.testConnection(country));
    }
}
