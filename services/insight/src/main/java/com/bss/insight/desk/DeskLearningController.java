package com.bss.insight.desk;

import com.bss.insight.api.ApiConstants;
import java.util.List;
import java.util.Map;
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
    public Map<String, Object> ingest(@RequestBody List<Map<String, Object>> batch) {
        return service.ingest(batch);
    }

    @GetMapping("/friction")
    public Map<String, Object> friction(@RequestParam(defaultValue = "7") int days) {
        return service.friction(days);
    }

    @GetMapping("/suggestions")
    public List<Map<String, Object>> suggestions() {
        return service.suggestions();
    }

    @PostMapping("/suggestions/{id}/accept")
    public Map<String, Object> accept(@PathVariable String id) {
        return service.decide(id, "accepted");
    }

    @PostMapping("/suggestions/{id}/dismiss")
    public Map<String, Object> dismiss(@PathVariable String id) {
        return service.decide(id, "dismissed");
    }

    @GetMapping("/presets")
    public List<Map<String, Object>> presets(@RequestParam(required = false) String desk, @RequestParam(required = false) String form) {
        return service.presets(desk, form);
    }

    /** Anonymised counts for the vendor's backlog feed. */
    @GetMapping("/export")
    public Map<String, Object> export(@RequestParam(defaultValue = "7") int days) {
        return service.export(days);
    }
}
