package com.bss.catalog.controller;

import com.bss.catalog.api.ApiConstants;
import com.bss.catalog.api.FieldSelector;
import com.bss.catalog.dto.ServiceCatalogJobDto;
import com.bss.catalog.entity.ServiceCatalogJob;
import com.bss.catalog.service.ServiceCatalogJobService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** TMF633 Service Catalog Management — ImportJob, on the v4 path and the v3 alias. */
@RestController
@Validated
@RequestMapping({ApiConstants.SERVICE_CATALOG_BASE_PATH + "/importJob",
        ApiConstants.SERVICE_CATALOG_V3_BASE_PATH + "/importJob"})
public class ImportJobController extends ServiceCatalogJobController {

    public ImportJobController(ServiceCatalogJobService service, FieldSelector fieldSelector) {
        super(service, fieldSelector, ServiceCatalogJob.IMPORT);
    }

    @GetMapping
    public ResponseEntity<List<?>> list(
            @RequestParam(name = "offset", defaultValue = "0") @Min(0) int offset,
            @RequestParam(name = "limit", defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(name = "fields", required = false) String fields,
            @RequestParam Map<String, String> allParams) {
        return listJobs(offset, limit, fields, allParams);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable("id") String id,
                                     @RequestParam(name = "fields", required = false) String fields) {
        return getJob(id, fields);
    }

    @PostMapping
    public ResponseEntity<ServiceCatalogJobDto> create(@Valid @RequestBody ServiceCatalogJobDto dto) {
        return createJob(dto);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") String id) {
        return deleteJob(id);
    }
}
