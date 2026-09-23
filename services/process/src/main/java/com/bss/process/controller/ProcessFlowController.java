package com.bss.process.controller;

import com.bss.process.api.ApiConstants;
import com.bss.process.api.FlowView;
import com.bss.process.api.SpecRequest;
import com.bss.process.api.SpecView;
import com.bss.process.api.TaskPatchRequest;
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

/** TMF701's two halves: specifications (design-time) and flows (run-time). */
@RestController
@RequestMapping(ApiConstants.BASE_PATH)
public class ProcessFlowController {

    private final ProcessFlowService service;

    public ProcessFlowController(ProcessFlowService service) {
        this.service = service;
    }

    @GetMapping("/processFlowSpecification")
    public ResponseEntity<List<SpecView>> specs() {
        return ResponseEntity.ok(service.listSpecs());
    }

    @PostMapping("/processFlowSpecification")
    public ResponseEntity<SpecView> upsertSpec(@RequestBody SpecRequest dto) {
        return ResponseEntity.ok(service.upsertSpec(dto));
    }

    @GetMapping("/processFlow")
    public ResponseEntity<List<FlowView>> flows(
            @RequestParam(required = false) String state,
            @RequestParam(name = "productOrderId", required = false) String productOrderId) {
        return ResponseEntity.ok(service.listFlows(state, productOrderId));
    }

    @GetMapping("/processFlow/{id}")
    public ResponseEntity<FlowView> flow(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.flowById(id));
    }

    @PatchMapping("/processFlow/{id}/taskFlow/{taskId}")
    public ResponseEntity<FlowView> patchTask(@PathVariable("id") String id,
            @PathVariable("taskId") String taskId, @RequestBody TaskPatchRequest dto) {
        return ResponseEntity.ok(service.patchTask(id, taskId, dto));
    }
}
