package com.bss.billing.controller;

import com.bss.billing.api.ApiConstants;
import com.bss.billing.service.MigrationRehearsalService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** The rehearsal door: run a legacy export against this catalog, read the
 *  saved receipts. Billing-admin ground. */
@RestController
@RequestMapping(ApiConstants.BASE_PATH + "/migrationRehearsal")
public class MigrationRehearsalController {

    private final MigrationRehearsalService rehearsal;

    public MigrationRehearsalController(MigrationRehearsalService rehearsal) {
        this.rehearsal = rehearsal;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> rehearse(@RequestBody Map<String, Object> request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(rehearsal.rehearse(request));
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list() {
        return ResponseEntity.ok(rehearsal.list());
    }
}
