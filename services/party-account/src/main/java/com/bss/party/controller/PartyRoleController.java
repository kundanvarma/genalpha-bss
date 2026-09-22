package com.bss.party.controller;

import com.bss.party.dto.PartyRoleRequest;
import com.bss.party.dto.PartyRoleView;
import com.bss.party.service.PartyRoleService;
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

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/tmf-api/partyRoleManagement/v4/partyRole")
public class PartyRoleController {

    private final PartyRoleService service;

    public PartyRoleController(PartyRoleService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<PartyRoleView> create(@RequestBody PartyRoleRequest dto) {
        PartyRoleView created = service.create(dto);
        return ResponseEntity.created(URI.create(created.href())).body(created);
    }

    @GetMapping
    public ResponseEntity<List<PartyRoleView>> list(@RequestParam Map<String, String> allParams) {
        Map<String, String> filters = new HashMap<>(allParams);
        filters.remove("offset");
        filters.remove("limit");
        filters.remove("fields");
        filters.remove("sort");
        return ResponseEntity.ok(service.findAll(filters));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PartyRoleView> get(@PathVariable String id) {
        return ResponseEntity.ok(service.findById(id));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<PartyRoleView> patch(@PathVariable String id, @RequestBody PartyRoleRequest dto) {
        return ResponseEntity.ok(service.patch(id, dto));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
