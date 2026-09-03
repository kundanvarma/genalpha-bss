package com.bss.appointment.controller;

import com.bss.appointment.api.ApiConstants;
import com.bss.appointment.provider.ScheduleProviders;
import com.bss.appointment.schedule.ScheduleService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * The operator's side of installation scheduling (appointment:admin): the
 * tenant's calendar and the technician roster that capacity derives from.
 * Vendor extension beside TMF646 — a real field-service system replaces
 * the roster behind the same searchTimeSlot seam.
 */
@RestController
@RequestMapping(ApiConstants.BASE_PATH)
public class ScheduleController {

    private final ScheduleService service;
    private final ScheduleProviders providers;

    public ScheduleController(ScheduleService service, ScheduleProviders providers) {
        this.service = service;
        this.providers = providers;
    }

    /** Reachability probe of the configured provider — never books anything. */
    @PostMapping("/scheduleConfig/test")
    public ResponseEntity<Map<String, Object>> test() {
        var cfg = service.current();
        var probe = providers.forConfig(cfg).probe(cfg);
        return ResponseEntity.ok(Map.of("provider", cfg.getProvider(), "ok", probe.ok(), "detail", probe.detail()));
    }

    @GetMapping("/scheduleConfig")
    public ResponseEntity<Map<String, Object>> config() {
        return ResponseEntity.ok(service.toMap(service.current()));
    }

    @PutMapping("/scheduleConfig")
    public ResponseEntity<Map<String, Object>> saveConfig(@RequestBody Map<String, Object> dto) {
        return ResponseEntity.ok(service.saveConfig(dto));
    }

    @GetMapping("/technician")
    public ResponseEntity<List<Map<String, Object>>> technicians() {
        List<Map<String, Object>> items = service.listTechnicians();
        return ResponseEntity.ok().header("X-Total-Count", String.valueOf(items.size())).body(items);
    }

    @GetMapping("/technician/{id}")
    public ResponseEntity<Map<String, Object>> technician(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.technician(id));
    }

    @PostMapping("/technician")
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> dto) {
        Map<String, Object> created = service.createTechnician(dto);
        return ResponseEntity.created(URI.create(ApiConstants.BASE_PATH + "/technician/" + created.get("id")))
                .body(created);
    }

    @PatchMapping("/technician/{id}")
    public ResponseEntity<Map<String, Object>> patch(@PathVariable("id") String id,
                                                     @RequestBody Map<String, Object> dto) {
        return ResponseEntity.ok(service.patchTechnician(id, dto));
    }

    @DeleteMapping("/technician/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") String id) {
        service.deleteTechnician(id);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
