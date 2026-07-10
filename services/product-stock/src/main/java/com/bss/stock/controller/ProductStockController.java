package com.bss.stock.controller;

import com.bss.stock.api.ApiConstants;
import com.bss.stock.api.FieldSelector;
import com.bss.stock.api.PagedResult;
import com.bss.stock.dto.ProductStockDto;
import com.bss.stock.service.ProductStockService;
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
@RequestMapping(ApiConstants.BASE_PATH + "/productStock")
public class ProductStockController {

    private final ProductStockService service;
    private final FieldSelector fieldSelector;

    public ProductStockController(ProductStockService service, FieldSelector fieldSelector) {
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
        PagedResult<ProductStockDto> result = service.findAll(offset, limit, filters);
        List<?> body = fields == null ? result.items() : fieldSelector.select(result.items(), fields);
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(result.totalCount()))
                .header("X-Result-Count", String.valueOf(result.items().size()))
                .body(body);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductStockDto> getById(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.findById(id));
    }

    @PostMapping
    public ResponseEntity<ProductStockDto> create(@Valid @RequestBody ProductStockDto dto) {
        ProductStockDto created = service.create(dto);
        return ResponseEntity
                .created(URI.create(created.getHref()))
                .body(created);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ProductStockDto> patch(@PathVariable("id") String id,
                                                 @RequestBody ProductStockDto patch) {
        return ResponseEntity.ok(service.patch(id, patch));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") String id) {
        service.delete(id);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
