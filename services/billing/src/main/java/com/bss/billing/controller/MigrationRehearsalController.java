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

import com.bss.billing.dto.MigrationRehearsalDtos.Report;
import com.bss.billing.dto.MigrationRehearsalDtos.Request;
import com.bss.billing.dto.MigrationRehearsalDtos.Summary;

import java.util.List;

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
    public ResponseEntity<Report> rehearse(@RequestBody Request request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(rehearsal.rehearse(request));
    }

    @GetMapping
    public ResponseEntity<List<Summary>> list() {
        return ResponseEntity.ok(rehearsal.list());
    }
}
