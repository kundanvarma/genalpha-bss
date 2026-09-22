package com.bss.quote.controller;

import com.bss.quote.api.ApiConstants;
import com.bss.quote.dto.ActivityLog;
import com.bss.quote.dto.ActivityView;
import com.bss.quote.dto.FunnelReport;
import com.bss.quote.dto.LeadRules.RoutingRuleView;
import com.bss.quote.dto.LeadRules.ScoringRuleView;
import com.bss.quote.dto.LeadView;
import com.bss.quote.dto.LineItem;
import com.bss.quote.dto.OpenTasks;
import com.bss.quote.dto.OpportunityView;
import com.bss.quote.dto.PipelineBoard;
import com.bss.quote.dto.QuotaAttainment;
import com.bss.quote.dto.QuotaView;
import com.bss.quote.dto.SalesReceipts.GuidedApplied;
import com.bss.quote.dto.SalesReceipts.QuoteHandoff;
import com.bss.quote.dto.SalesReceipts.SocialImport;
import com.bss.quote.dto.SalesRequests.ActivityRequest;
import com.bss.quote.dto.SalesRequests.LeadPatch;
import com.bss.quote.dto.SalesRequests.LeadRequest;
import com.bss.quote.dto.SalesRequests.OpportunityPatch;
import com.bss.quote.dto.SalesRequests.QuotaRequest;
import com.bss.quote.dto.SalesRequests.RoutingRuleRequest;
import com.bss.quote.dto.SalesRequests.ScoringRuleRequest;
import com.bss.quote.dto.SnapshotView;
import com.bss.quote.dto.WonReport;
import com.bss.quote.service.SalesService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping(ApiConstants.SALES_BASE)
public class SalesController {

    private final SalesService service;

    public SalesController(SalesService service) {
        this.service = service;
    }

    /** Open capture: a prospect (or any channel) may knock without a token. */
    @PostMapping("/salesLead")
    public ResponseEntity<LeadView> createLead(@RequestBody LeadRequest dto) {
        LeadView created = service.createLead(dto);
        return ResponseEntity.created(URI.create(created.href())).body(created);
    }

    @GetMapping("/salesLead")
    public ResponseEntity<List<LeadView>> leads() {
        return ResponseEntity.ok(service.findLeads());
    }

    @GetMapping("/salesLead/{id}")
    public ResponseEntity<LeadView> lead(@PathVariable String id) {
        return ResponseEntity.ok(service.findLead(id));
    }

    /** qualified (mints the opportunity) or unqualified — once. */
    @PatchMapping("/salesLead/{id}")
    public ResponseEntity<LeadView> patchLead(@PathVariable String id, @RequestBody LeadPatch patch) {
        return ResponseEntity.ok(service.patchLead(id, patch));
    }

    /** Pull the tenant's social lead-gen form into the pipeline (idempotent). */
    @PostMapping("/salesLead/importSocial")
    public ResponseEntity<SocialImport> importSocial() {
        return ResponseEntity.ok(service.importSocial());
    }

    // ---- lead scoring + routing ----

    @PostMapping("/salesLead/scoringRule")
    public ResponseEntity<ScoringRuleView> createScoringRule(@RequestBody ScoringRuleRequest dto) {
        return ResponseEntity.ok(service.createScoringRule(dto));
    }

    @GetMapping("/salesLead/scoringRule")
    public ResponseEntity<List<ScoringRuleView>> scoringRules() {
        return ResponseEntity.ok(service.listScoringRules());
    }

    @PostMapping("/salesLead/routingRule")
    public ResponseEntity<RoutingRuleView> createRoutingRule(@RequestBody RoutingRuleRequest dto) {
        return ResponseEntity.ok(service.createRoutingRule(dto));
    }

    @GetMapping("/salesLead/routingRule")
    public ResponseEntity<List<RoutingRuleView>> routingRules() {
        return ResponseEntity.ok(service.listRoutingRules());
    }

    /** Recompute a lead's score/grade/owner after the rules changed. */
    @PostMapping("/salesLead/{id}/score")
    public ResponseEntity<LeadView> rescore(@PathVariable String id) {
        return ResponseEntity.ok(service.rescoreLead(id));
    }

    @GetMapping("/salesOpportunity")
    public ResponseEntity<List<OpportunityView>> opportunities() {
        return ResponseEntity.ok(service.findOpportunities());
    }

    /** The pipeline board: open deals per stage + the weighted forecast. */
    @GetMapping("/salesOpportunity/pipeline")
    public ResponseEntity<PipelineBoard> pipeline() {
        return ResponseEntity.ok(service.pipeline());
    }

    /** Won deals grouped by the programme that sourced the lead. */
    @GetMapping("/salesOpportunity/wonReport")
    public ResponseEntity<WonReport> wonReport() {
        return ResponseEntity.ok(service.wonReport());
    }

    /** Funnel analytics: stage conversion, win rate, cycle time, time-in-stage
     *  — structured + a narrative summary a copilot can read. */
    @GetMapping("/salesOpportunity/funnel")
    public ResponseEntity<FunnelReport> funnel() {
        return ResponseEntity.ok(service.funnel());
    }

    /** The open next-step tasks across the pipeline (optionally one assignee's). */
    @GetMapping("/salesOpportunity/tasks")
    public ResponseEntity<OpenTasks> tasks(@RequestParam(required = false) String assignee) {
        return ResponseEntity.ok(service.openTasks(assignee));
    }

    @GetMapping("/salesOpportunity/{id}")
    public ResponseEntity<OpportunityView> opportunity(@PathVariable String id) {
        return ResponseEntity.ok(service.findOpportunity(id));
    }

    /** Work the deal: move a stage, set value/close-date/owner, or close
     *  it won/lost — only the fields in the patch change. */
    @PatchMapping("/salesOpportunity/{id}")
    public ResponseEntity<OpportunityView> patchOpportunity(@PathVariable String id,
            @RequestBody OpportunityPatch patch) {
        return ResponseEntity.ok(service.patchOpportunity(id, patch));
    }

    /** Add a catalog offering as a line on the deal. */
    @PostMapping("/salesOpportunity/{id}/item")
    public ResponseEntity<OpportunityView> addItem(@PathVariable String id, @RequestBody LineItem dto) {
        return ResponseEntity.ok(service.addItem(id, dto));
    }

    @DeleteMapping("/salesOpportunity/{id}/item/{itemId}")
    public ResponseEntity<OpportunityView> removeItem(@PathVariable String id, @PathVariable String itemId) {
        return ResponseEntity.ok(service.removeItem(id, itemId));
    }

    /** Log a call/email/note, or set a next-step task (with dueDate). Mirrors to the 360. */
    @PostMapping("/salesOpportunity/{id}/activity")
    public ResponseEntity<ActivityLog> logActivity(@PathVariable String id, @RequestBody ActivityRequest dto) {
        return ResponseEntity.ok(service.logActivity(id, dto));
    }

    /** Mark an open task done. */
    @PostMapping("/salesOpportunity/{id}/activity/{activityId}/done")
    public ResponseEntity<ActivityView> completeTask(@PathVariable String id, @PathVariable String activityId) {
        return ResponseEntity.ok(service.completeTask(id, activityId));
    }

    /** CPQ hand-off: build a TMF648 quote from the deal's line items. */
    @PostMapping("/salesOpportunity/{id}/quote")
    public ResponseEntity<QuoteHandoff> buildQuote(@PathVariable String id) {
        return ResponseEntity.ok(service.buildQuote(id));
    }

    /** Guided selling → deal: add the recommended offerings as line items.
     *  The body is the questionnaire's answers (an open document). */
    @PostMapping("/salesOpportunity/{id}/applyGuided")
    public ResponseEntity<GuidedApplied> applyGuided(@PathVariable String id, @RequestBody JsonNode answers) {
        return ResponseEntity.ok(service.applyGuided(id, answers));
    }

    // ---- quota + attainment ----

    @PostMapping("/salesOpportunity/quota")
    public ResponseEntity<QuotaView> createQuota(@RequestBody QuotaRequest dto) {
        return ResponseEntity.ok(service.createQuota(dto));
    }

    @GetMapping("/salesOpportunity/quota")
    public ResponseEntity<List<QuotaView>> quotas() {
        return ResponseEntity.ok(service.listQuotas());
    }

    /** Quota attainment for a period: quota vs won vs weighted-open per owner + team. */
    @GetMapping("/salesOpportunity/quotaAttainment")
    public ResponseEntity<QuotaAttainment> quotaAttainment(@RequestParam String period) {
        return ResponseEntity.ok(service.quotaAttainment(period));
    }

    /** Capture the current open weighted forecast (forecast-over-time). */
    @PostMapping("/salesOpportunity/snapshot")
    public ResponseEntity<SnapshotView> snapshot() {
        return ResponseEntity.ok(service.captureSnapshot());
    }

    @GetMapping("/salesOpportunity/snapshots")
    public ResponseEntity<List<SnapshotView>> snapshots() {
        return ResponseEntity.ok(service.listSnapshots());
    }
}
