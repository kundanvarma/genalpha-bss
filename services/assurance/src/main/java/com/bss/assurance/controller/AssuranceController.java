package com.bss.assurance.controller;

import com.bss.assurance.api.ApiConstants;
import com.bss.assurance.service.AssuranceService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class AssuranceController {

    private final AssuranceService service;

    public AssuranceController(AssuranceService service) {
        this.service = service;
    }

    @PostMapping(ApiConstants.ALARM_BASE + "/alarm")
    public ResponseEntity<Map<String, Object>> raise(@RequestBody Map<String, Object> dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.raiseAlarm(dto));
    }

    @GetMapping(ApiConstants.ALARM_BASE + "/alarm")
    public ResponseEntity<List<Map<String, Object>>> alarms(
            @RequestParam(required = false) String state) {
        return ResponseEntity.ok(service.alarms(state));
    }

    @GetMapping(ApiConstants.PROBLEM_BASE + "/serviceProblem")
    public ResponseEntity<List<Map<String, Object>>> problems(
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(service.problems(status));
    }

    @PatchMapping(ApiConstants.PROBLEM_BASE + "/serviceProblem/{id}")
    public ResponseEntity<Map<String, Object>> patch(@PathVariable String id,
            @RequestBody Map<String, Object> patch) {
        if (!"resolved".equals(patch.get("status"))) {
            throw new com.bss.assurance.exception.BadRequestException(
                    "the only supported transition is status: 'resolved'");
        }
        return ResponseEntity.ok(service.resolveProblem(id));
    }
}
