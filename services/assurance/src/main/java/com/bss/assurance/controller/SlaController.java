package com.bss.assurance.controller;

import com.bss.assurance.dto.SlaView;
import com.bss.assurance.dto.SlaViolationView;
import com.bss.assurance.service.SlaService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** TMF623 read faces: the promises in force, and the ones that broke. */
@RestController
@RequestMapping("/tmf-api/slaManagement/v4")
public class SlaController {

    private final SlaService service;

    public SlaController(SlaService service) {
        this.service = service;
    }

    @GetMapping("/sla")
    public ResponseEntity<List<SlaView>> slas() {
        return ResponseEntity.ok(service.listSlas());
    }

    @GetMapping("/slaViolation")
    public ResponseEntity<List<SlaViolationView>> violations() {
        return ResponseEntity.ok(service.listViolations());
    }
}
