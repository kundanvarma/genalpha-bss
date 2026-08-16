package com.bss.quote.controller;

import com.bss.quote.api.ApiConstants;
import com.bss.quote.service.SalesService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(ApiConstants.SALES_BASE)
public class SalesController {

    private final SalesService service;

    public SalesController(SalesService service) {
        this.service = service;
    }

    /** Open capture: a prospect (or any channel) may knock without a token. */
    @PostMapping("/salesLead")
    public ResponseEntity<Map<String, Object>> createLead(@RequestBody Map<String, Object> dto) {
        Map<String, Object> created = service.createLead(dto);
        return ResponseEntity.created(URI.create(String.valueOf(created.get("href")))).body(created);
    }

    @GetMapping("/salesLead")
    public ResponseEntity<List<Map<String, Object>>> leads() {
        return ResponseEntity.ok(service.findLeads());
    }

    @GetMapping("/salesLead/{id}")
    public ResponseEntity<Map<String, Object>> lead(@PathVariable String id) {
        return ResponseEntity.ok(service.findLead(id));
    }

    /** qualified (mints the opportunity) or unqualified — once. */
    @PatchMapping("/salesLead/{id}")
    public ResponseEntity<Map<String, Object>> patchLead(@PathVariable String id,
            @RequestBody Map<String, Object> patch) {
        return ResponseEntity.ok(service.patchLead(id, patch));
    }

    /** Pull the tenant's social lead-gen form into the pipeline (idempotent). */
    @PostMapping("/salesLead/importSocial")
    public ResponseEntity<Map<String, Object>> importSocial() {
        return ResponseEntity.ok(service.importSocial());
    }

    @GetMapping("/salesOpportunity")
    public ResponseEntity<List<Map<String, Object>>> opportunities() {
        return ResponseEntity.ok(service.findOpportunities());
    }

    /** The pipeline board: open deals per stage + the weighted forecast. */
    @GetMapping("/salesOpportunity/pipeline")
    public ResponseEntity<Map<String, Object>> pipeline() {
        return ResponseEntity.ok(service.pipeline());
    }

    /** Won deals grouped by the programme that sourced the lead. */
    @GetMapping("/salesOpportunity/wonReport")
    public ResponseEntity<Map<String, Object>> wonReport() {
        return ResponseEntity.ok(service.wonReport());
    }

    /** The open next-step tasks across the pipeline (optionally one assignee's). */
    @GetMapping("/salesOpportunity/tasks")
    public ResponseEntity<Map<String, Object>> tasks(
            @org.springframework.web.bind.annotation.RequestParam(required = false) String assignee) {
        return ResponseEntity.ok(service.openTasks(assignee));
    }

    @GetMapping("/salesOpportunity/{id}")
    public ResponseEntity<Map<String, Object>> opportunity(@PathVariable String id) {
        return ResponseEntity.ok(service.findOpportunity(id));
    }

    /** Work the deal: move a stage, set value/close-date/owner, or close
     *  it won/lost — only the fields in the patch change. */
    @PatchMapping("/salesOpportunity/{id}")
    public ResponseEntity<Map<String, Object>> patchOpportunity(@PathVariable String id,
            @RequestBody Map<String, Object> patch) {
        return ResponseEntity.ok(service.patchOpportunity(id, patch));
    }

    /** Add a catalog offering as a line on the deal. */
    @PostMapping("/salesOpportunity/{id}/item")
    public ResponseEntity<Map<String, Object>> addItem(@PathVariable String id,
            @RequestBody Map<String, Object> dto) {
        return ResponseEntity.ok(service.addItem(id, dto));
    }

    @DeleteMapping("/salesOpportunity/{id}/item/{itemId}")
    public ResponseEntity<Map<String, Object>> removeItem(@PathVariable String id,
            @PathVariable String itemId) {
        return ResponseEntity.ok(service.removeItem(id, itemId));
    }

    /** Log a call/email/note, or set a next-step task (with dueDate). Mirrors to the 360. */
    @PostMapping("/salesOpportunity/{id}/activity")
    public ResponseEntity<Map<String, Object>> logActivity(@PathVariable String id,
            @RequestBody Map<String, Object> dto) {
        return ResponseEntity.ok(service.logActivity(id, dto));
    }

    /** Mark an open task done. */
    @PostMapping("/salesOpportunity/{id}/activity/{activityId}/done")
    public ResponseEntity<Map<String, Object>> completeTask(@PathVariable String id,
            @PathVariable String activityId) {
        return ResponseEntity.ok(service.completeTask(id, activityId));
    }

    /** CPQ hand-off: build a TMF648 quote from the deal's line items. */
    @PostMapping("/salesOpportunity/{id}/quote")
    public ResponseEntity<Map<String, Object>> buildQuote(@PathVariable String id) {
        return ResponseEntity.ok(service.buildQuote(id));
    }
}
