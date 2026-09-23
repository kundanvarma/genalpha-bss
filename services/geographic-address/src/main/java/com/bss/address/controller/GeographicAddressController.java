package com.bss.address.controller;

import com.bss.address.api.ApiConstants;
import com.bss.address.api.PagedResult;
import com.bss.address.dto.AddressValidationRequest;
import com.bss.address.dto.AddressValidationResult;
import com.bss.address.dto.GeographicAddressRequest;
import com.bss.address.dto.GeographicAddressView;
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
    public ResponseEntity<AddressValidationResult> validate(
            @RequestBody AddressValidationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.validate(request));
    }

    @PostMapping(ApiConstants.BASE_PATH + "/geographicAddress")
    public ResponseEntity<GeographicAddressView> create(
            @RequestBody GeographicAddressRequest dto) {
        GeographicAddressView created = service.create(dto);
        return ResponseEntity.created(URI.create(created.href())).body(created);
    }

    @GetMapping(ApiConstants.BASE_PATH + "/geographicAddress")
    public ResponseEntity<List<GeographicAddressView>> list(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam Map<String, String> params) {
        Map<String, String> filters = new HashMap<>(params);
        filters.remove("offset");
        filters.remove("limit");
        PagedResult<GeographicAddressView> result = service.findAll(offset, limit, filters);
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(result.totalCount()))
                .body(result.items());
    }

    @GetMapping(ApiConstants.BASE_PATH + "/geographicAddress/{id}")
    public ResponseEntity<GeographicAddressView> get(@PathVariable String id) {
        return ResponseEntity.ok(service.findById(id));
    }
}
