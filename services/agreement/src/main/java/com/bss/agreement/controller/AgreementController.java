package com.bss.agreement.controller;

import com.bss.agreement.api.ApiConstants;
import com.bss.agreement.api.PagedResult;
import com.bss.agreement.dto.AgreementPatch;
import com.bss.agreement.dto.AgreementRequest;
import com.bss.agreement.dto.AgreementView;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bss.agreement.service.AgreementService;
import org.springframework.http.ResponseEntity;
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
import java.util.Map;

@RestController
@RequestMapping(ApiConstants.BASE_PATH + "/agreement")
public class AgreementController {

    private final AgreementService service;
    private final ObjectMapper objectMapper;

    public AgreementController(AgreementService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<AgreementView> create(@RequestBody AgreementRequest dto) {
        AgreementView created = service.create(dto);
        return ResponseEntity.created(URI.create(created.href())).body(created);
    }

    @GetMapping
    public ResponseEntity<java.util.List<AgreementView>> list(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam Map<String, String> params) {
        Map<String, String> filters = new HashMap<>(params);
        filters.remove("offset");
        filters.remove("limit");
        PagedResult<AgreementView> result = service.findAll(offset, limit, filters);
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(result.totalCount()))
                .body(result.items());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Object> get(@PathVariable String id,
            @RequestParam(required = false) String fields) {
        AgreementView agreement = service.findById(id);
        if (fields == null || fields.isBlank()) {
            return ResponseEntity.ok(agreement);
        }
        // TMF630 attribute selection, strict: exactly the asked-for fields.
        // The record renders to a tree in its own declared order first, so a
        // key the view leaves off is still absent from the projection.
        ObjectNode full = objectMapper.valueToTree(agreement);
        ObjectNode slim = objectMapper.createObjectNode();
        for (String f : fields.split(",")) {
            String key = f.trim();
            if (full.has(key)) {
                slim.set(key, full.get(key));
            }
        }
        return ResponseEntity.ok(slim);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<AgreementView> patch(@PathVariable String id,
            @RequestBody AgreementPatch patch) {
        return ResponseEntity.ok(service.patch(id, patch));
    }
}
