package com.bss.intelligence.controller;

import com.bss.intelligence.aimanagement.AiManagementStore;
import com.bss.intelligence.exception.BadRequestException;
import com.bss.intelligence.service.ContractPatch;
import com.bss.intelligence.service.Tmf915Service;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 *
 * The wire is the open edge: stored documents are the caller's JSON kept
 * verbatim; the projections are records, rendered to the same tree so
 * TMF630 filtering and field selection work on both alike.
 */
@RestController
@RequestMapping("/tmf-api/aiManagement/v4")
public class Tmf915Controller {

    /** the stored-only resources; aiModel and aiModelContract have their own doors below */
    private static final String KIND =
            "{kind:alarm|rule|aiContract|aiContractSpecification|aiContractViolation|aiModelSpecification}";

    private final Tmf915Service service;
    private final AiManagementStore store;
    private final ObjectMapper objectMapper;

    public Tmf915Controller(Tmf915Service service, AiManagementStore store, ObjectMapper objectMapper) {
        this.service = service;
        this.store = store;
        this.objectMapper = objectMapper;
    }

    /* ---------- the registered resources ---------- */

    @PostMapping("/" + KIND)
    public ResponseEntity<ObjectNode> create(@PathVariable("kind") String kind,
            @RequestBody JsonNode body) {
        return created(store.create(kind, body));
    }

    @GetMapping("/" + KIND)
    public ResponseEntity<List<ObjectNode>> list(@PathVariable("kind") String kind,
            @RequestParam Map<String, String> params) {
        return page(store.list(kind), params);
    }

    @GetMapping("/" + KIND + "/{id}")
    public ResponseEntity<ObjectNode> get(@PathVariable("kind") String kind,
            @PathVariable("id") String id, @RequestParam(required = false) String fields) {
        return ResponseEntity.ok(AiManagementStore.select(store.find(kind, id), fields));
    }

    @PatchMapping("/" + KIND + "/{id}")
    public ResponseEntity<ObjectNode> patch(@PathVariable("kind") String kind,
            @PathVariable("id") String id, @RequestBody JsonNode patch) {
        return ResponseEntity.ok(store.patch(kind, id, patch));
    }

    @DeleteMapping("/" + KIND + "/{id}")
    public ResponseEntity<Void> delete(@PathVariable("kind") String kind, @PathVariable("id") String id) {
        store.delete(kind, id);
        return ResponseEntity.noContent().build();
    }

    /* ---------- aiModel: registered rows beside what the ledger proves ---------- */

    @PostMapping("/aiModel")
    public ResponseEntity<ObjectNode> createModel(@RequestBody JsonNode body) {
        return created(store.create("aiModel", body));
    }

    @GetMapping("/aiModel")
    public ResponseEntity<List<ObjectNode>> models(@RequestParam Map<String, String> params) {
        List<ObjectNode> all = new ArrayList<>(store.list("aiModel"));
        service.listModels().forEach(m -> all.add(tree(m)));
        return page(all, params);
    }

    @GetMapping("/aiModel/{id}")
    public ResponseEntity<ObjectNode> model(@PathVariable("id") String id,
            @RequestParam(required = false) String fields) {
        return ResponseEntity.ok(AiManagementStore.select(store.findModel(id)
                .orElseGet(() -> tree(service.findModel(id))), fields));
    }

    /** the ledger's ids are provider/model — two segments */
    @GetMapping("/aiModel/{provider}/{model}")
    public ResponseEntity<ObjectNode> servedModel(@PathVariable("provider") String provider,
            @PathVariable("model") String model, @RequestParam(required = false) String fields) {
        return ResponseEntity.ok(AiManagementStore.select(
                tree(service.findModel(provider + "/" + model)), fields));
    }

    @PatchMapping("/aiModel/{id}")
    public ResponseEntity<ObjectNode> patchModel(@PathVariable("id") String id,
            @RequestBody JsonNode patch) {
        return ResponseEntity.ok(store.patch("aiModel", id, patch));
    }

    @DeleteMapping("/aiModel/{id}")
    public ResponseEntity<Void> deleteModel(@PathVariable("id") String id) {
        store.delete("aiModel", id);
        return ResponseEntity.noContent().build();
    }

    /* ---------- aiModelContract: the scenarios, with their numbers ---------- */

    @GetMapping("/aiModelContract")
    public ResponseEntity<List<ObjectNode>> contracts(@RequestParam Map<String, String> params) {
        return page(service.listContracts().stream().map(this::tree).toList(), params);
    }

    @GetMapping("/aiModelContract/{id}")
    public ResponseEntity<ObjectNode> contract(@PathVariable("id") String id,
            @RequestParam(required = false) String fields) {
        return ResponseEntity.ok(AiManagementStore.select(tree(service.findContract(id)), fields));
    }

    /** The in-life lever: {state: suspended|active, note} — ai:admin only. */
    @PatchMapping("/aiModelContract/{id}")
    public ResponseEntity<ObjectNode> patch(@PathVariable("id") String id,
            @RequestBody ContractPatch patch) {
        return ResponseEntity.ok(tree(service.patchContract(id, patch)));
    }

    /* ---------- shared shape ---------- */

    /** A projection record as the same JSON tree a stored document is — one list mechanic for both. */
    private ObjectNode tree(Object record) {
        return objectMapper.valueToTree(record);
    }

    private static ResponseEntity<ObjectNode> created(ObjectNode view) {
        return ResponseEntity.created(URI.create(view.path("href").asText())).body(view);
    }

    private static ResponseEntity<List<ObjectNode>> page(List<ObjectNode> rows,
            Map<String, String> params) {
        List<ObjectNode> matched = AiManagementStore.filter(rows, params);
        int offset = intParam(params, "offset", 0);
        int limit = intParam(params, "limit", Integer.MAX_VALUE);
        List<ObjectNode> window = offset >= matched.size() ? List.of()
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
