package com.bss.catalog.controller;

import com.bss.catalog.dto.CheckProductConfiguration;
import com.bss.catalog.dto.ProductConfigurationRequest;
import com.bss.catalog.dto.QueryProductConfiguration;
import com.bss.catalog.service.ConfiguratorService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * TMF760 Product Configuration (v5 — the API of the Product Configurator
 * ODA component; no v4 was ever published). Two task resources, instant
 * sync: the POST computes and answers from the catalog's own rows — no
 * task table, nothing persisted. Anonymous like the catalog it reads:
 * configuring IS browsing, and the gateway's hostname header decides
 * which tenant's public price list a guest is configuring against.
 */
@RestController
@RequestMapping("/tmf-api/productConfigurationManagement/v5")
public class ProductConfigurationController {

    private final ConfiguratorService configurator;

    public ProductConfigurationController(ConfiguratorService configurator) {
        this.configurator = configurator;
    }

    /** The configuration space of an offering: groups, pickers, prices. */
    @PostMapping("/queryProductConfiguration")
    public ResponseEntity<QueryProductConfiguration> query(@RequestBody ProductConfigurationRequest.Query request) {
        return ResponseEntity.ok(configurator.query(request));
    }

    /** Is this pick set orderable — and what does it cost? */
    @PostMapping("/checkProductConfiguration")
    public ResponseEntity<CheckProductConfiguration> check(@RequestBody ProductConfigurationRequest.Check request) {
        return ResponseEntity.ok(configurator.check(request));
    }
}
