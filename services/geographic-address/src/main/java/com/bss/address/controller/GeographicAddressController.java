package com.bss.address.controller;

import com.bss.address.api.ApiConstants;
import com.bss.address.api.PagedResult;
import com.bss.address.service.GeographicAddressService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
public class GeographicAddressController {

    private final GeographicAddressService service;

    public GeographicAddressController(GeographicAddressService service) {
        this.service = service;
    }

    /** Anonymous shop-window validation. */
    @PostMapping(ApiConstants.BASE_PATH + "/geographicAddressValidation")
    public ResponseEntity<Map<String, Object>> validate(@RequestBody Map<String, Object> request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.validate(request));
    }

    @PostMapping(ApiConstants.BASE_PATH + "/geographicAddress")
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> dto) {
        Map<String, Object> created = service.create(dto);
        return ResponseEntity.created(URI.create(String.valueOf(created.get("href")))).body(created);
    }

    @GetMapping(ApiConstants.BASE_PATH + "/geographicAddress")
    public ResponseEntity<List<Map<String, Object>>> list(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam Map<String, String> params) {
        Map<String, String> filters = new HashMap<>(params);
        filters.remove("offset");
        filters.remove("limit");
        PagedResult<Map<String, Object>> result = service.findAll(offset, limit, filters);
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(result.totalCount()))
                .body(result.items());
    }

    @GetMapping(ApiConstants.BASE_PATH + "/geographicAddress/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
        return ResponseEntity.ok(service.findById(id));
    }
}
