package com.bss.ordering.controller;

import com.bss.ordering.api.ApiConstants;
import com.bss.ordering.api.PagedResult;
import com.bss.ordering.dto.ProductOrderDto;
import com.bss.ordering.service.ProductOrderService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@Validated
@RequestMapping(ApiConstants.BASE_PATH + "/productOrder")
public class ProductOrderController {

    private final ProductOrderService service;
    private final ObjectMapper objectMapper;

    public ProductOrderController(ProductOrderService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
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
        PagedResult<ProductOrderDto> result = service.findAll(offset, limit, filters);
        List<?> body = fields == null ? result.items() : selectFields(result.items(), fields);
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(result.totalCount()))
                .header("X-Result-Count", String.valueOf(result.items().size()))
                .body(body);
    }

    /**
     * TMF630 attribute selection: return only the requested fields. The id is
     * always included so results stay addressable.
     */
    private List<Map<String, Object>> selectFields(List<ProductOrderDto> items, String fields) {
        Set<String> keep = new LinkedHashSet<>(Arrays.asList(fields.split(",")));
        keep.add("id");
        return items.stream()
                .map(dto -> objectMapper.convertValue(dto, new TypeReference<LinkedHashMap<String, Object>>() {
                }))
                .map(full -> {
                    Map<String, Object> selected = new LinkedHashMap<>();
                    for (String key : keep) {
                        if (full.get(key) != null) {
                            selected.put(key, full.get(key));
                        }
                    }
                    return selected;
                })
                .toList();
    }

    /** The hub's approvals inbox: held orders across the caller's family. */
    @GetMapping("/familyApprovals")
    public ResponseEntity<java.util.List<ProductOrderDto>> familyApprovals() {
        return ResponseEntity.ok(service.familyApprovals());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductOrderDto> getById(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.findById(id));
    }

    /** Approve (release) or deny (cancel) a held ask-to-buy order. */
    @PostMapping("/{id}/approval")
    public ResponseEntity<ProductOrderDto> approval(@PathVariable("id") String id,
            @RequestBody java.util.Map<String, Object> body) {
        boolean approve = Boolean.TRUE.equals(body.get("approve"));
        return ResponseEntity.ok(service.decideApproval(id, approve));
    }

    @PostMapping
    public ResponseEntity<ProductOrderDto> create(@Valid @RequestBody ProductOrderDto dto) {
        ProductOrderDto created = service.create(dto);
        return ResponseEntity
                .created(URI.create(created.getHref()))
                .body(created);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ProductOrderDto> patch(@PathVariable("id") String id,
                                                 @RequestBody ProductOrderDto patch) {
        return ResponseEntity.ok(service.patch(id, patch));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") String id) {
        service.delete(id);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
