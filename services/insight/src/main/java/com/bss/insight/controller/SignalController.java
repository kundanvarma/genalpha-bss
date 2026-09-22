package com.bss.insight.controller;

import com.bss.insight.api.ApiConstants;
import com.bss.insight.dto.ClassificationInput;
import com.bss.insight.dto.FlywheelPair;
import com.bss.insight.dto.SignalClassificationView;
import com.bss.insight.dto.SignalInput;
import com.bss.insight.dto.SignalTwin;
import com.bss.insight.dto.SignalView;
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
    public ResponseEntity<SignalView> ingest(@RequestBody SignalInput dto) {
        SignalView out = service.ingest(dto);
        return ResponseEntity.status(out.duplicated() ? HttpStatus.OK : HttpStatus.CREATED).body(out);
    }

    @GetMapping
    public ResponseEntity<List<SignalView>> list(
            @RequestParam(required = false) String source,
            @RequestParam(required = false, defaultValue = "false") boolean unclassified) {
        return ResponseEntity.ok(service.list(source, unclassified));
    }

    /** The flywheel's fine-tune corpus (T-P4): evidence-verified pairs in
     * TWIN SPACE — zero real facts by construction. */
    @GetMapping("/flywheel/dataset")
    public ResponseEntity<List<FlywheelPair>> flywheelDataset() {
        return ResponseEntity.ok(service.flywheelDataset());
    }

    /** The twin (Tvilling T-P1): the fiction that would leave in T-P2 —
     * back-office read, so an operator can SEE what the frontier would see. */
    @GetMapping("/{signalId}/twin")
    public ResponseEntity<SignalTwin> twin(@PathVariable String signalId) {
        return ResponseEntity.ok(service.twinOf(signalId));
    }

    /** The battery's write-back (SI-P3) — evidence quotes verified at the
     * store; a classification that cannot cite its source is refused. */
    @PostMapping("/{signalId}/classification")
    public ResponseEntity<SignalClassificationView> classify(@PathVariable String signalId,
            @RequestBody ClassificationInput dto) {
        return ResponseEntity.status(201).body(service.classify(signalId, dto));
    }
}
