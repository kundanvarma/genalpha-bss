package com.bss.party.controller;

import com.bss.party.api.ApiConstants;
import com.bss.party.api.FieldSelector;
import com.bss.party.api.PagedResult;
import com.bss.party.dto.IndividualDto;
import com.bss.party.service.IndividualService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
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
@Validated
@RequestMapping(ApiConstants.PARTY_BASE + "/individual")
public class IndividualController {

    private final IndividualService service;
    private final FieldSelector fieldSelector;

    public IndividualController(IndividualService service, FieldSelector fieldSelector) {
        this.service = service;
        this.fieldSelector = fieldSelector;
    }

    @GetMapping
    public ResponseEntity<List<?>> list(
            @RequestParam(name = "offset", defaultValue = "0") @Min(0) int offset,
            @RequestParam(name = "limit", defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(name = "fields", required = false) String fields,
            @RequestParam Map<String, String> allParams) {
        Map<String, String> filters = new HashMap<>(allParams);
        filters.remove("offset");
        filters.remove("limit");
        filters.remove("fields");
        PagedResult<IndividualDto> result = service.findAll(offset, limit, filters);
        List<?> body = fields == null ? result.items() : fieldSelector.select(result.items(), fields);
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(result.totalCount()))
                .header("X-Result-Count", String.valueOf(result.items().size()))
                .body(body);
    }

    @GetMapping("/{id}")
    public ResponseEntity<IndividualDto> getById(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.findById(id));
    }

    @PostMapping
    public ResponseEntity<IndividualDto> create(@Valid @RequestBody IndividualDto dto) {
        IndividualDto created = service.create(dto);
        return ResponseEntity
                .created(URI.create(created.getHref()))
                .body(created);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<IndividualDto> patch(@PathVariable("id") String id,
                                               @RequestBody IndividualDto patch) {
        return ResponseEntity.ok(service.patch(id, patch));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") String id) {
        service.delete(id);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    // ---------------- household billing (consent endpoints) ----------------

    @PostMapping("/{id}/householdPayer")
    public ResponseEntity<IndividualDto> requestHouseholdPayer(@PathVariable String id,
            @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(service.requestHouseholdPayer(id,
                body == null ? null : String.valueOf(body.get("payerEmail"))));
    }

    @PostMapping("/{id}/householdPayer/accept")
    public ResponseEntity<IndividualDto> acceptHouseholdPayer(@PathVariable String id) {
        return ResponseEntity.ok(service.acceptHouseholdPayer(id));
    }

    @DeleteMapping("/{id}/householdPayer")
    public ResponseEntity<IndividualDto> clearHouseholdPayer(@PathVariable String id) {
        return ResponseEntity.ok(service.clearHouseholdPayer(id));
    }

    /** Child accounts: the payer creates the dependent — link born active. */
    @PostMapping("/{id}/dependents")
    public ResponseEntity<IndividualDto> createDependent(@PathVariable String id,
            @RequestBody IndividualDto dto) {
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED)
                .body(service.createDependent(id, dto));
    }

    @GetMapping("/{id}/household")
    public ResponseEntity<Map<String, Object>> household(@PathVariable String id) {
        return ResponseEntity.ok(service.householdOf(id));
    }
}
