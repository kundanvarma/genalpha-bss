package com.bss.qualification.controller;

import com.bss.qualification.api.ApiConstants;
import com.bss.qualification.api.PagedResult;
import com.bss.qualification.service.QualificationService;
import com.bss.qualification.service.ServiceableAreaService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
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

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The TMF679 check is anonymous shop-window functionality (a prospect asks
 * "can I get fiber here?" before having any account); the serviceable-area
 * rule data behind it is back-office CRUD.
 */
@RestController
@Validated
@RequestMapping(ApiConstants.BASE_PATH)
public class QualificationController {

    private final QualificationService qualification;
    private final ServiceableAreaService areas;

    public QualificationController(QualificationService qualification, ServiceableAreaService areas) {
        this.qualification = qualification;
        this.areas = areas;
    }

    @PostMapping("/checkProductOfferingQualification")
    public ResponseEntity<Map<String, Object>> check(@RequestBody Map<String, Object> request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(qualification.check(request));
    }

    @GetMapping("/serviceableArea")
    public ResponseEntity<List<Map<String, Object>>> list(
            @RequestParam(name = "offset", defaultValue = "0") @Min(0) int offset,
            @RequestParam(name = "limit", defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam Map<String, String> allParams) {
        Map<String, String> filters = new HashMap<>(allParams);
        filters.remove("offset");
        filters.remove("limit");
        PagedResult<Map<String, Object>> result = areas.findAll(offset, limit, filters);
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(result.totalCount()))
                .header("X-Result-Count", String.valueOf(result.items().size()))
                .body(result.items());
    }

    @GetMapping("/serviceableArea/{id}")
    public ResponseEntity<Map<String, Object>> getById(@PathVariable("id") String id) {
        return ResponseEntity.ok(areas.findById(id));
    }

    @PostMapping("/serviceableArea")
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> dto) {
        Map<String, Object> created = areas.create(dto);
        return ResponseEntity.created(URI.create(String.valueOf(created.get("href")))).body(created);
    }

    @DeleteMapping("/serviceableArea/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") String id) {
        areas.delete(id);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
