package com.bss.campaign.controller;

import com.bss.campaign.api.ApiConstants;
import com.bss.campaign.dto.AttributionReport;
import com.bss.campaign.dto.CampaignExecutionView;
import com.bss.campaign.dto.CampaignPatch;
import com.bss.campaign.dto.CampaignRequest;
import com.bss.campaign.dto.CampaignStats;
import com.bss.campaign.dto.CampaignView;
import com.bss.campaign.dto.ExecutionReceipt;
import com.bss.campaign.service.AttributionService;
import com.bss.campaign.service.CampaignService;
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

@RestController
@RequestMapping(ApiConstants.BASE_PATH + "/campaign")
public class CampaignController {

    private final CampaignService service;
    private final AttributionService attribution;

    public CampaignController(CampaignService service, AttributionService attribution) {
        this.service = service;
        this.attribution = attribution;
    }

    @PostMapping
    public ResponseEntity<CampaignView> create(@RequestBody CampaignRequest dto) {
        CampaignView created = service.create(dto);
        return ResponseEntity.created(URI.create(created.href())).body(created);
    }

    @GetMapping
    public ResponseEntity<List<CampaignView>> list() {
        return ResponseEntity.ok(service.findAll());
    }

    /** Portfolio attribution: lift + incremental revenue across EVERY campaign
     * and journey, one marketer readout (literal path — precedes /{id}/... rules). */
    @GetMapping("/attribution")
    public ResponseEntity<AttributionReport> attribution() {
        return ResponseEntity.ok(attribution.report());
    }

    @PatchMapping("/{id}")
    public ResponseEntity<CampaignView> patch(@PathVariable String id, @RequestBody CampaignPatch patch) {
        return ResponseEntity.ok(service.patch(id, patch));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Segment blast: reach everyone in the campaign's insight segment, once. */
    @PostMapping("/{id}/execute")
    public ResponseEntity<ExecutionReceipt> execute(@PathVariable String id) {
        return ResponseEntity.ok(service.executeSegment(id));
    }

    /** The measurement readout: reached / held out / conversions / lift. */
    @GetMapping("/{id}/stats")
    public ResponseEntity<CampaignStats> stats(@PathVariable String id) {
        return ResponseEntity.ok(service.statsOf(id));
    }

    @GetMapping("/{id}/execution")
    public ResponseEntity<List<CampaignExecutionView>> executions(@PathVariable String id) {
        return ResponseEntity.ok(service.executionsOf(id));
    }
}
