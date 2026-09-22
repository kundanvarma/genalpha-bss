package com.bss.party.controller;

import com.bss.party.api.ApiConstants;
import com.bss.party.dto.DirectoryExportReceipt;
import com.bss.party.dto.DirectoryExportRunDetail;
import com.bss.party.dto.DirectorySettingRequest;
import com.bss.party.dto.DirectorySettingView;
import com.bss.party.service.DirectoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The directory-services obligation: a customer's (or staff's) exposure
 * choices per party/service, and the back-office delta export the number-
 * directory agreement requires. Suppression rules live in DirectoryService.
 */
@RestController
@RequestMapping(ApiConstants.PARTY_BASE)
public class DirectoryController {

    private final DirectoryService service;

    public DirectoryController(DirectoryService service) {
        this.service = service;
    }

    @GetMapping("/individual/{id}/directorySetting")
    public ResponseEntity<List<DirectorySettingView>> list(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.listSettings(id));
    }

    /** Upsert by (party, serviceRef): {serviceRef?, exposure?, secretNumber?}. */
    @PostMapping("/individual/{id}/directorySetting")
    public ResponseEntity<DirectorySettingView> upsert(@PathVariable("id") String id,
            @RequestBody DirectorySettingRequest body) {
        return ResponseEntity.ok(service.upsertSetting(id, body.serviceRef(), body.exposure(),
                body.secretNumber()));
    }

    @PostMapping("/directoryExport/run")
    public ResponseEntity<DirectoryExportReceipt> run() {
        return ResponseEntity.ok(service.runExport());
    }

    @GetMapping("/directoryExport/{runId}")
    public ResponseEntity<DirectoryExportRunDetail> get(@PathVariable("runId") String runId) {
        return ResponseEntity.ok(service.getRun(runId));
    }
}
