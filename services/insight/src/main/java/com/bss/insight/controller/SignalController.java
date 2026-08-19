package com.bss.insight.controller;

import com.bss.insight.api.ApiConstants;
import com.bss.insight.service.SignalService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The signal store's API (SI-P1). POST is the connectors' door (and the
 * suites'); GET is the back-office read. Everything through POST passes the
 * PII firewall before it is stored — there is no way to persist raw text.
 */
@RestController
@RequestMapping(ApiConstants.BASE_PATH + "/signal")
public class SignalController {

    private final SignalService service;

    public SignalController(SignalService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> ingest(@RequestBody Map<String, Object> dto) {
        Map<String, Object> out = service.ingest(dto);
        return ResponseEntity.status(Boolean.TRUE.equals(out.get("duplicate"))
                ? HttpStatus.OK : HttpStatus.CREATED).body(out);
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(
            @RequestParam(required = false) String source,
            @RequestParam(required = false, defaultValue = "false") boolean unclassified) {
        return ResponseEntity.ok(service.list(source, unclassified));
    }

    /** The twin (Tvilling T-P1): the fiction that would leave in T-P2 —
     * back-office read, so an operator can SEE what the frontier would see. */
    @GetMapping("/{signalId}/twin")
    public ResponseEntity<Map<String, Object>> twin(@PathVariable String signalId) {
        return ResponseEntity.ok(service.twinOf(signalId));
    }

    /** The battery's write-back (SI-P3) — evidence quotes verified at the
     * store; a classification that cannot cite its source is refused. */
    @PostMapping("/{signalId}/classification")
    public ResponseEntity<Map<String, Object>> classify(@PathVariable String signalId,
            @RequestBody Map<String, Object> dto) {
        return ResponseEntity.status(201).body(service.classify(signalId, dto));
    }
}
