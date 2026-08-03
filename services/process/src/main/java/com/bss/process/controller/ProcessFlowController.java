package com.bss.process.controller;

import com.bss.process.api.ApiConstants;
import com.bss.process.service.ProcessFlowService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** TMF701's two halves: specifications (design-time) and flows (run-time). */
@RestController
@RequestMapping(ApiConstants.BASE_PATH)
public class ProcessFlowController {

    private final ProcessFlowService service;

    public ProcessFlowController(ProcessFlowService service) {
        this.service = service;
    }

    @GetMapping("/processFlowSpecification")
    public ResponseEntity<List<Map<String, Object>>> specs() {
        return ResponseEntity.ok(service.listSpecs());
    }

    @PostMapping("/processFlowSpecification")
    public ResponseEntity<Map<String, Object>> upsertSpec(@RequestBody Map<String, Object> dto) {
        return ResponseEntity.ok(service.upsertSpec(dto));
    }

    @GetMapping("/processFlow")
    public ResponseEntity<List<Map<String, Object>>> flows(
            @RequestParam(required = false) String state,
            @RequestParam(name = "productOrderId", required = false) String productOrderId) {
        return ResponseEntity.ok(service.listFlows(state, productOrderId));
    }

    @GetMapping("/processFlow/{id}")
    public ResponseEntity<Map<String, Object>> flow(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.flowById(id));
    }

    @PatchMapping("/processFlow/{id}/taskFlow/{taskId}")
    public ResponseEntity<Map<String, Object>> patchTask(@PathVariable("id") String id,
            @PathVariable("taskId") String taskId, @RequestBody Map<String, Object> dto) {
        return ResponseEntity.ok(service.patchTask(id, taskId, dto));
    }
}
