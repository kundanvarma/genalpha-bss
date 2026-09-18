package com.bss.catalog.controller;

import com.bss.catalog.api.ApiConstants;
import com.bss.catalog.api.FieldSelector;
import com.bss.catalog.api.PagedResult;
import com.bss.catalog.dto.ServiceCandidateDto;
import com.bss.catalog.service.ServiceCandidateService;
import jakarta.servlet.http.HttpServletRequest;
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

/** TMF633 Service Catalog Management — ServiceCandidate, on the v4 path and the v3 alias. */
@RestController
@Validated
@RequestMapping({ApiConstants.SERVICE_CATALOG_BASE_PATH + "/serviceCandidate",
        ApiConstants.SERVICE_CATALOG_V3_BASE_PATH + "/serviceCandidate"})
public class ServiceCandidateController {

    private final ServiceCandidateService service;
    private final FieldSelector fieldSelector;

    public ServiceCandidateController(ServiceCandidateService service, FieldSelector fieldSelector) {
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
        PagedResult<ServiceCandidateDto> result = service.findAll(offset, limit, filters);
        List<?> body = fields == null ? result.items() : fieldSelector.select(result.items(), fields, "href");
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(result.totalCount()))
                .header("X-Result-Count", String.valueOf(result.items().size()))
                .body(body);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable("id") String id,
                                     @RequestParam(name = "fields", required = false) String fields) {
        ServiceCandidateDto dto = service.findById(id);
        if (fields == null || fields.isBlank()) {
            return ResponseEntity.ok(dto);
        }
        return ResponseEntity.ok(fieldSelector.select(List.of(dto), fields, "href").get(0));
    }

    @PostMapping
    public ResponseEntity<ServiceCandidateDto> create(@Valid @RequestBody ServiceCandidateDto dto) {
        ServiceCandidateDto created = service.create(dto);
        return ResponseEntity
                .created(URI.create(created.getHref()))
                .body(created);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ServiceCandidateDto> patch(@PathVariable("id") String id,
                                                     @RequestBody ServiceCandidateDto patch,
                                                     HttpServletRequest request) {
        HttpStatus status = ApiConstants.isServiceCatalogV3(request) ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(service.patch(id, patch));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") String id) {
        service.delete(id);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
