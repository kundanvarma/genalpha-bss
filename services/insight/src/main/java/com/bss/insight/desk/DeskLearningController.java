package com.bss.insight.desk;

import com.bss.insight.api.ApiConstants;
import com.bss.insight.dto.DeskEventInput;
import com.bss.insight.dto.DeskExport;
import com.bss.insight.dto.DeskIngestReceipt;
import com.bss.insight.dto.DeskPresetView;
import com.bss.insight.dto.DeskSuggestion;
import com.bss.insight.dto.FrictionReport;
import com.bss.insight.dto.SuggestionDecision;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiConstants.BASE_PATH + "/desk")
public class DeskLearningController {

    private final DeskLearningService service;

    public DeskLearningController(DeskLearningService service) {
        this.service = service;
    }

    /** Desks post batches of what staff did. Any authenticated staff user of the tenant. */
    @PostMapping("/event")
    public DeskIngestReceipt ingest(@RequestBody List<DeskEventInput> batch) {
        return service.ingest(batch);
    }

    @GetMapping("/friction")
    public FrictionReport friction(@RequestParam(defaultValue = "7") int days) {
        return service.friction(days);
    }

    @GetMapping("/suggestions")
    public List<DeskSuggestion> suggestions() {
        return service.suggestions();
    }

    @PostMapping("/suggestions/{id}/accept")
    public SuggestionDecision accept(@PathVariable String id) {
        return service.decide(id, "accepted");
    }

    @PostMapping("/suggestions/{id}/dismiss")
    public SuggestionDecision dismiss(@PathVariable String id) {
        return service.decide(id, "dismissed");
    }

    @GetMapping("/presets")
    public List<DeskPresetView> presets(@RequestParam(required = false) String desk, @RequestParam(required = false) String form) {
        return service.presets(desk, form);
    }

    /** Anonymised counts for the vendor's backlog feed. */
    @GetMapping("/export")
    public DeskExport export(@RequestParam(defaultValue = "7") int days) {
        return service.export(days);
    }
}
