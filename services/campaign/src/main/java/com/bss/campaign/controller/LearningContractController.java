package com.bss.campaign.controller;

import com.bss.campaign.api.ApiConstants;
import com.bss.campaign.service.LearningContractService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Learning contracts: the intent per DecisionPoint, as configuration. Reads need campaign:read, writes campaign:write. */
@RestController
@RequestMapping(ApiConstants.BASE_PATH + "/learningContract")
public class LearningContractController {

    private final LearningContractService service;

    public LearningContractController(LearningContractService service) {
        this.service = service;
    }

    /** Every point with its contract, or the defaults marked as such. */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list() {
        return ResponseEntity.ok(service.effective());
    }

    @GetMapping("/{decisionPoint}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String decisionPoint) {
        return ResponseEntity.ok(service.get(decisionPoint));
    }

    @PutMapping("/{decisionPoint}")
    public ResponseEntity<Map<String, Object>> put(@PathVariable String decisionPoint,
            @RequestBody Map<String, Object> dto) {
        return ResponseEntity.ok(service.put(decisionPoint, dto));
    }

    /** Back to the defaults; old decision records keep citing the versions they ran under. */
    @DeleteMapping("/{decisionPoint}")
    public ResponseEntity<Void> delete(@PathVariable String decisionPoint) {
        service.delete(decisionPoint);
        return ResponseEntity.noContent().build();
    }

    /** What the point would decide for a sample context under the current contract — nothing recorded. */
    @PostMapping("/{decisionPoint}/dryRun")
    public ResponseEntity<Map<String, Object>> dryRun(@PathVariable String decisionPoint,
            @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(service.dryRun(decisionPoint, body));
    }
}
