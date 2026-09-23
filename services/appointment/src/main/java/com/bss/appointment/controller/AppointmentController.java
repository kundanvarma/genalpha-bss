package com.bss.appointment.controller;

import com.bss.appointment.api.ApiConstants;
import com.bss.appointment.api.PagedResult;
import com.bss.appointment.dto.AppointmentPatch;
import com.bss.appointment.dto.AppointmentRequest;
import com.bss.appointment.dto.AppointmentView;
import com.bss.appointment.dto.SearchTimeSlotRequest;
import com.bss.appointment.dto.SearchTimeSlotResult;
import com.bss.appointment.service.AppointmentService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
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

/**
 * Slot availability is shop-window information (a guest sees install slots
 * before checkout); booking and reading appointments requires identity.
 */
@RestController
@Validated
@RequestMapping(ApiConstants.BASE_PATH)
public class AppointmentController {

    private final AppointmentService service;

    public AppointmentController(AppointmentService service) {
        this.service = service;
    }

    @PostMapping("/searchTimeSlot")
    public ResponseEntity<SearchTimeSlotResult> searchTimeSlot(
            @RequestBody(required = false) SearchTimeSlotRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.searchTimeSlot(body));
    }

    @GetMapping("/appointment")
    public ResponseEntity<List<AppointmentView>> list(
            @RequestParam(name = "offset", defaultValue = "0") @Min(0) int offset,
            @RequestParam(name = "limit", defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(name = "relatedPartyId", required = false) String relatedPartyId) {
        PagedResult<AppointmentView> result = service.findAll(offset, limit, relatedPartyId);
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(result.totalCount()))
                .header("X-Result-Count", String.valueOf(result.items().size()))
                .body(result.items());
    }

    @GetMapping("/appointment/{id}")
    public ResponseEntity<AppointmentView> getById(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.findById(id));
    }

    @PostMapping("/appointment")
    public ResponseEntity<AppointmentView> create(@RequestBody AppointmentRequest dto) {
        AppointmentView created = service.create(dto);
        return ResponseEntity.created(URI.create(String.valueOf(created.href()))).body(created);
    }

    @PatchMapping("/appointment/{id}")
    public ResponseEntity<AppointmentView> patch(@PathVariable("id") String id,
                                                 @RequestBody AppointmentPatch patch) {
        return ResponseEntity.ok(service.patch(id, patch));
    }
}
