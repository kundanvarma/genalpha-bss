package com.bss.party.controller;

import com.bss.party.api.ApiConstants;
import com.bss.party.service.DirectoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

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
    public ResponseEntity<List<Map<String, Object>>> list(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.listSettings(id));
    }

    /** Upsert by (party, serviceRef): {serviceRef?, exposure?, secretNumber?}. */
    @PostMapping("/individual/{id}/directorySetting")
    public ResponseEntity<Map<String, Object>> upsert(@PathVariable("id") String id,
            @RequestBody Map<String, Object> body) {
        Map<String, Object> safe = body == null ? Map.of() : body;
        return ResponseEntity.ok(service.upsertSetting(id,
                safe.get("serviceRef") == null ? null : String.valueOf(safe.get("serviceRef")),
                safe.get("exposure") == null ? null : String.valueOf(safe.get("exposure")),
                safe.get("secretNumber") == null ? null
                        : Boolean.valueOf(String.valueOf(safe.get("secretNumber")))));
    }

    @PostMapping("/directoryExport/run")
    public ResponseEntity<Map<String, Object>> run() {
        return ResponseEntity.ok(service.runExport());
    }

    @GetMapping("/directoryExport/{runId}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable("runId") String runId) {
        return ResponseEntity.ok(service.getRun(runId));
    }
}
