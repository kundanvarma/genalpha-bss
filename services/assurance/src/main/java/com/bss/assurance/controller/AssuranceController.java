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
            @RequestParam(required = false) String id,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String alarmRaisedTime,
            @RequestParam(required = false) String probableCause,
            @RequestParam(required = false) String sourceSystemId,
            @RequestParam(required = false) String fields) {
        Map<String, String> filters = new java.util.LinkedHashMap<>();
        filters.put("id", id);
        filters.put("state", state);
        filters.put("alarmRaisedTime", alarmRaisedTime);
        filters.put("probableCause", probableCause);
        filters.put("sourceSystemId", sourceSystemId);
        return ResponseEntity.ok(service.alarms(filters, fields));
    }

    @GetMapping(ApiConstants.ALARM_BASE + "/alarm/{id}")
    public ResponseEntity<Map<String, Object>> alarmById(@PathVariable String id) {
        return ResponseEntity.ok(service.alarmById(id));
    }

    @PatchMapping(ApiConstants.ALARM_BASE + "/alarm/{id}")
    public ResponseEntity<Map<String, Object>> patchAlarm(@PathVariable String id,
            @RequestBody Map<String, Object> patch) {
        return ResponseEntity.ok(service.patchAlarm(id, patch));
    }

    @GetMapping(ApiConstants.PROBLEM_BASE + "/serviceProblem")
    public ResponseEntity<List<Map<String, Object>>> problems(
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(service.problems(status));
    }

    @GetMapping(ApiConstants.PROBLEM_BASE + "/serviceProblem/{id}")
    public ResponseEntity<Map<String, Object>> problemById(@PathVariable String id) {
        return ResponseEntity.ok(service.problemById(id));
    }

    @PostMapping(ApiConstants.PROBLEM_BASE + "/serviceProblem")
    public ResponseEntity<Map<String, Object>> declareProblem(
            @RequestBody Map<String, Object> dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createProblem(dto));
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
