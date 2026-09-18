package com.bss.intelligence.controller;

import com.bss.intelligence.aimanagement.AiManagementStore;
import com.bss.intelligence.exception.BadRequestException;
import com.bss.intelligence.service.Tmf915Service;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * TMF915 AI Management: the control plane, standards-addressable. Two halves
 * share one face. The PROJECTED half is what the audit ledger proves has
 * served (aiModel) and the governed scenarios with their real monitoring
 * numbers (aiModelContract) — nothing registered, everything observed. The
 * REGISTERED half is the standard's declarative resources — alarm, rule,
 * aiContract and its specification, aiContractViolation,
 * aiModelSpecification, and explicitly registered aiModel rows — kept in a
 * tenant-scoped store. Reads are ops-grade (ai:use); every write rides
 * ai:admin, the seam the governance controller always said production
 * would need.
 */
@RestController
@RequestMapping("/tmf-api/aiManagement/v4")
public class Tmf915Controller {

    /** the stored-only resources; aiModel and aiModelContract have their own doors below */
    private static final String KIND =
            "{kind:alarm|rule|aiContract|aiContractSpecification|aiContractViolation|aiModelSpecification}";

    private final Tmf915Service service;
    private final AiManagementStore store;

    public Tmf915Controller(Tmf915Service service, AiManagementStore store) {
        this.service = service;
        this.store = store;
    }

    /* ---------- the registered resources ---------- */

    @PostMapping("/" + KIND)
    public ResponseEntity<Map<String, Object>> create(@PathVariable("kind") String kind,
            @RequestBody Map<String, Object> body) {
        return created(store.create(kind, body));
    }

    @GetMapping("/" + KIND)
    public ResponseEntity<List<Map<String, Object>>> list(@PathVariable("kind") String kind,
            @RequestParam Map<String, String> params) {
        return page(store.list(kind), params);
    }

    @GetMapping("/" + KIND + "/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable("kind") String kind,
            @PathVariable("id") String id, @RequestParam(required = false) String fields) {
        return ResponseEntity.ok(AiManagementStore.select(store.find(kind, id), fields));
    }

    @PatchMapping("/" + KIND + "/{id}")
    public ResponseEntity<Map<String, Object>> patch(@PathVariable("kind") String kind,
            @PathVariable("id") String id, @RequestBody Map<String, Object> patch) {
        return ResponseEntity.ok(store.patch(kind, id, patch));
    }

    @DeleteMapping("/" + KIND + "/{id}")
    public ResponseEntity<Void> delete(@PathVariable("kind") String kind, @PathVariable("id") String id) {
        store.delete(kind, id);
        return ResponseEntity.noContent().build();
    }

    /* ---------- aiModel: registered rows beside what the ledger proves ---------- */

    @PostMapping("/aiModel")
    public ResponseEntity<Map<String, Object>> createModel(@RequestBody Map<String, Object> body) {
        return created(store.create("aiModel", body));
    }

    @GetMapping("/aiModel")
    public ResponseEntity<List<Map<String, Object>>> models(@RequestParam Map<String, String> params) {
        List<Map<String, Object>> all = new ArrayList<>(store.list("aiModel"));
        all.addAll(service.listModels());
        return page(all, params);
    }

    @GetMapping("/aiModel/{id}")
    public ResponseEntity<Map<String, Object>> model(@PathVariable("id") String id,
            @RequestParam(required = false) String fields) {
        return ResponseEntity.ok(AiManagementStore.select(store.findModel(id)
                .orElseGet(() -> service.findModel(id)), fields));
    }

    /** the ledger's ids are provider/model — two segments */
    @GetMapping("/aiModel/{provider}/{model}")
    public ResponseEntity<Map<String, Object>> servedModel(@PathVariable("provider") String provider,
            @PathVariable("model") String model, @RequestParam(required = false) String fields) {
        return ResponseEntity.ok(AiManagementStore.select(service.findModel(provider + "/" + model), fields));
    }

    @PatchMapping("/aiModel/{id}")
    public ResponseEntity<Map<String, Object>> patchModel(@PathVariable("id") String id,
            @RequestBody Map<String, Object> patch) {
        return ResponseEntity.ok(store.patch("aiModel", id, patch));
    }

    @DeleteMapping("/aiModel/{id}")
    public ResponseEntity<Void> deleteModel(@PathVariable("id") String id) {
        store.delete("aiModel", id);
        return ResponseEntity.noContent().build();
    }

    /* ---------- aiModelContract: the scenarios, with their numbers ---------- */

    @GetMapping("/aiModelContract")
    public ResponseEntity<List<Map<String, Object>>> contracts(@RequestParam Map<String, String> params) {
        return page(service.listContracts(), params);
    }

    @GetMapping("/aiModelContract/{id}")
    public ResponseEntity<Map<String, Object>> contract(@PathVariable("id") String id,
            @RequestParam(required = false) String fields) {
        return ResponseEntity.ok(AiManagementStore.select(service.findContract(id), fields));
    }

    /** The in-life lever: {state: suspended|active, note} — ai:admin only. */
    @PatchMapping("/aiModelContract/{id}")
    public ResponseEntity<Map<String, Object>> patch(@PathVariable("id") String id,
            @RequestBody Map<String, Object> patch) {
        return ResponseEntity.ok(service.patchContract(id, patch));
    }

    /* ---------- shared shape ---------- */

    private static ResponseEntity<Map<String, Object>> created(Map<String, Object> view) {
        return ResponseEntity.created(URI.create(String.valueOf(view.get("href")))).body(view);
    }

    private static ResponseEntity<List<Map<String, Object>>> page(List<Map<String, Object>> rows,
            Map<String, String> params) {
        List<Map<String, Object>> matched = AiManagementStore.filter(rows, params);
        int offset = intParam(params, "offset", 0);
        int limit = intParam(params, "limit", Integer.MAX_VALUE);
        List<Map<String, Object>> window = offset >= matched.size() ? List.of()
                : matched.subList(offset, (int) Math.min((long) offset + limit, matched.size()));
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(matched.size()))
                .header("X-Result-Count", String.valueOf(window.size()))
                .body(AiManagementStore.select(window, params.get("fields")));
    }

    private static int intParam(Map<String, String> params, String key, int fallback) {
        String raw = params.get(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(0, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            throw new BadRequestException(key + ": invalid value");
        }
    }
}
