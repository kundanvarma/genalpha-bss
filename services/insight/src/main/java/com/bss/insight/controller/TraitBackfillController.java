package com.bss.insight.controller;

import com.bss.insight.api.ApiConstants;
import com.bss.insight.service.PartyTraitService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * CDP backfill: ingest traits for EXISTING customers whose account/order events
 * predate the trait listener. The event bus fills traits going forward; this
 * one-shot admin ingest seeds the history, so a customer created before the CDP
 * existed is still reachable by a trait audience — no browsing required. An
 * orchestrator (ops/backfill_cdp.js) reads party + inventory and posts the
 * traits here; this endpoint only writes, under the caller's tenant.
 */
@RestController
@RequestMapping(ApiConstants.BASE_PATH + "/traits")
public class TraitBackfillController {

    private final PartyTraitService traits;

    public TraitBackfillController(PartyTraitService traits) {
        this.traits = traits;
    }

    /** Batch write: {traits:[{partyId,key,value,multi?}]}. multi=true adds to a
     * multi-valued trait (e.g. product holdings); otherwise it replaces. */
    @PostMapping("/backfill")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> backfill(@RequestBody Map<String, Object> body) {
        Object raw = body.get("traits");
        List<Map<String, Object>> items = raw instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        int written = 0;
        int skipped = 0;
        for (Map<String, Object> t : items) {
            String partyId = str(t.get("partyId"));
            String key = str(t.get("key"));
            String value = str(t.get("value"));
            if (partyId == null || key == null || value == null || value.isBlank()) {
                skipped++;
                continue;
            }
            if (Boolean.TRUE.equals(t.get("multi"))) {
                traits.upsert(partyId, key, value);
            } else {
                traits.setTrait(partyId, key, value);
            }
            written++;
        }
        return ResponseEntity.ok(Map.of("written", written, "skipped", skipped));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
