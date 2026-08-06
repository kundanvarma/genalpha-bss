package com.bss.agreement.controller;

import com.bss.agreement.service.PartnershipTypeService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * TMF668 Partnership Type Management: the vocabulary for a fleet full of
 * partners. Types are tenant catalog data — reads for anyone who may read
 * agreements, writes for whoever signs them.
 */
@RestController
@RequestMapping("/tmf-api/partnershipTypeManagement/v4")
public class PartnershipTypeController {

    private final PartnershipTypeService service;

    public PartnershipTypeController(PartnershipTypeService service) {
        this.service = service;
    }

    @GetMapping({"/partnershipType", "/partnershipType/"})
    public ResponseEntity<List<Map<String, Object>>> list() {
        return ResponseEntity.ok(service.findAll());
    }

    @GetMapping("/partnershipType/{id}")
    public ResponseEntity<Map<String, Object>> byId(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.findById(id));
    }

    @PostMapping({"/partnershipType", "/partnershipType/"})
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> dto,
            jakarta.servlet.http.HttpServletRequest request) {
        Map<String, Object> created = service.create(dto);
        // Location = the URL the CLIENT posted to, plus the new id — rebuilt
        // from the gateway's X-Forwarded headers so it matches their view
        String host = request.getHeader("X-Forwarded-Host");
        String proto = request.getHeader("X-Forwarded-Proto");
        String location = host != null
                ? (proto == null ? "http" : proto.split(",")[0].trim()) + "://"
                        + host.split(",")[0].trim() + request.getRequestURI()
                        + (request.getRequestURI().endsWith("/") ? "" : "/") + created.get("id")
                : String.valueOf(created.get("href"));
        return ResponseEntity.created(URI.create(location)).body(created);
    }

    @DeleteMapping("/partnershipType/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") String id) {
        service.delete(id);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
