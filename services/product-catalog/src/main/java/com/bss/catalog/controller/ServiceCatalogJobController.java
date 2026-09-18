package com.bss.catalog.controller;

import com.bss.catalog.api.FieldSelector;
import com.bss.catalog.api.PagedResult;
import com.bss.catalog.dto.ServiceCatalogJobDto;
import com.bss.catalog.service.ServiceCatalogJobService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The shared body of the TMF633 ImportJob and ExportJob faces: the two
 * controllers below only pick the path and the kind. Jobs are created, listed,
 * read and deleted — never patched (a job is a request, not a document).
 */
abstract class ServiceCatalogJobController {

    private final ServiceCatalogJobService service;
    private final FieldSelector fieldSelector;
    private final String kind;

    ServiceCatalogJobController(ServiceCatalogJobService service, FieldSelector fieldSelector, String kind) {
        this.service = service;
        this.fieldSelector = fieldSelector;
        this.kind = kind;
    }

    ResponseEntity<List<?>> listJobs(int offset, int limit, String fields, Map<String, String> allParams) {
        Map<String, String> filters = new HashMap<>(allParams);
        filters.remove("offset");
        filters.remove("limit");
        filters.remove("fields");
        PagedResult<ServiceCatalogJobDto> result = service.findAll(kind, offset, limit, filters);
        List<?> body = fields == null ? result.items() : fieldSelector.select(result.items(), fields, "href");
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(result.totalCount()))
                .header("X-Result-Count", String.valueOf(result.items().size()))
                .body(body);
    }

    ResponseEntity<?> getJob(String id, String fields) {
        ServiceCatalogJobDto dto = service.findById(kind, id);
        if (fields == null || fields.isBlank()) {
            return ResponseEntity.ok(dto);
        }
        return ResponseEntity.ok(fieldSelector.select(List.of(dto), fields, "href").get(0));
    }

    ResponseEntity<ServiceCatalogJobDto> createJob(ServiceCatalogJobDto dto) {
        ServiceCatalogJobDto created = service.create(kind, dto);
        return ResponseEntity.created(URI.create(created.getHref())).body(created);
    }

    ResponseEntity<Void> deleteJob(String id) {
        service.delete(kind, id);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
