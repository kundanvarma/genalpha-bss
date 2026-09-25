package com.bss.revenue.controller;

import com.bss.revenue.api.ApiConstants;
import com.bss.revenue.dto.ConfigChangeRequest;
import com.bss.revenue.dto.ConfigChangeView;
import com.bss.revenue.service.ConfigChangeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * /revenue/v1/configChange — the ladder a change to live financial
 * configuration climbs. Reads ride billing:read with the rest of the subledger;
 * every rung is billing:admin (the catch-all in SecurityConfig), because each
 * one moves the tenant's books a step closer to a different answer.
 */
@RestController
@RequestMapping(ApiConstants.BASE_PATH + "/configChange")
public class ConfigChangeController {

    private final ConfigChangeService service;

    public ConfigChangeController(ConfigChangeService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<ConfigChangeView>> list(
            @RequestParam(name = "state", required = false) String state) {
        return ResponseEntity.ok(service.list(state));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ConfigChangeView> one(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.one(id));
    }

    /** A record body, never a raw map: the state and the dates are the store's, not the caller's. */
    @PostMapping
    public ResponseEntity<ConfigChangeView> draft(@RequestBody ConfigChangeRequest dto) {
        return ResponseEntity.ok(service.draft(dto));
    }

    @PostMapping("/{id}/validate")
    public ResponseEntity<ConfigChangeView> validate(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.validate(id));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<ConfigChangeView> approve(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.approve(id));
    }

    @PostMapping("/{id}/activate")
    public ResponseEntity<ConfigChangeView> activate(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.activate(id));
    }

    @PostMapping("/{id}/withdraw")
    public ResponseEntity<ConfigChangeView> withdraw(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.withdraw(id));
    }
}
