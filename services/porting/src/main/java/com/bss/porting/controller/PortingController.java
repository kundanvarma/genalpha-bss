package com.bss.porting.controller;

import com.bss.porting.api.ApiConstants;
import com.bss.porting.dto.PortedNumber;
import com.bss.porting.dto.PortingOrderRequest;
import com.bss.porting.dto.PortingOrderView;
import com.bss.porting.service.PortingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping(ApiConstants.BASE_PATH)
public class PortingController {

    private final PortingService service;

    public PortingController(PortingService service) {
        this.service = service;
    }

    @PostMapping("/numberPortingOrder")
    public ResponseEntity<PortingOrderView> create(@RequestBody PortingOrderRequest dto) {
        PortingOrderView created = service.create(dto);
        return ResponseEntity.created(URI.create(created.href())).body(created);
    }

    @GetMapping("/numberPortingOrder")
    public ResponseEntity<List<PortingOrderView>> list(
            @RequestParam(name = "relatedPartyId", required = false) String relatedPartyId,
            @RequestParam(name = "status", required = false) String status) {
        return ResponseEntity.ok(service.findAll(relatedPartyId, status));
    }

    @GetMapping("/numberPortingOrder/{id}")
    public ResponseEntity<PortingOrderView> byId(@PathVariable String id) {
        return ResponseEntity.ok(service.findById(id));
    }

    @PostMapping("/numberPortingOrder/{id}/complete")
    public ResponseEntity<PortingOrderView> complete(@PathVariable String id) {
        return ResponseEntity.ok(service.complete(id));
    }

    /** Internal seam for the orchestrator: the number this party ported in. */
    @GetMapping("/portedNumber")
    public ResponseEntity<PortedNumber> portedNumber(@RequestParam String relatedPartyId) {
        return ResponseEntity.ok(service.portedNumberFor(relatedPartyId));
    }
}
