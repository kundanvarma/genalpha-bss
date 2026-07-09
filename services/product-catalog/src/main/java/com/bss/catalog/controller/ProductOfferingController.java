package com.bss.catalog.controller;

import com.bss.catalog.api.ApiConstants;
import com.bss.catalog.api.PagedResult;
import com.bss.catalog.dto.ProductOfferingDto;
import com.bss.catalog.service.ProductOfferingService;
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
import java.util.List;

@RestController
@Validated
@RequestMapping(ApiConstants.BASE_PATH + "/productOffering")
public class ProductOfferingController {

    private final ProductOfferingService service;

    public ProductOfferingController(ProductOfferingService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<ProductOfferingDto>> list(
            @RequestParam(name = "offset", defaultValue = "0") @Min(0) int offset,
            @RequestParam(name = "limit", defaultValue = "20") @Min(1) @Max(100) int limit) {
        PagedResult<ProductOfferingDto> result = service.findAll(offset, limit);
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(result.totalCount()))
                .header("X-Result-Count", String.valueOf(result.items().size()))
                .body(result.items());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductOfferingDto> getById(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.findById(id));
    }

    @PostMapping
    public ResponseEntity<ProductOfferingDto> create(@Valid @RequestBody ProductOfferingDto dto) {
        ProductOfferingDto created = service.create(dto);
        return ResponseEntity
                .created(URI.create(created.getHref()))
                .body(created);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ProductOfferingDto> patch(@PathVariable("id") String id,
                                                    @RequestBody ProductOfferingDto patch) {
        return ResponseEntity.ok(service.patch(id, patch));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") String id) {
        service.delete(id);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
