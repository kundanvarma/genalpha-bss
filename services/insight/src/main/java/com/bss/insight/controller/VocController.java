package com.bss.insight.controller;

import com.bss.insight.api.ApiConstants;
import com.bss.insight.service.VocService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Voice of Customer (SI-P4): the aggregate read + the on-demand deviation
 * sweep (demos and tests don't wait for the hourly schedule). */
@RestController
@RequestMapping(ApiConstants.BASE_PATH + "/voc")
public class VocController {

    private final VocService service;

    public VocController(VocService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary() {
        return ResponseEntity.ok(service.summary());
    }

    @PostMapping("/sweep")
    public ResponseEntity<Map<String, Object>> sweep() {
        return ResponseEntity.ok(service.deviationSweep());
    }
}
