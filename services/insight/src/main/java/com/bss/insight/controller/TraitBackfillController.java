package com.bss.insight.controller;

import com.bss.insight.api.ApiConstants;
import com.bss.insight.dto.BackfillRequest;
import com.bss.insight.service.PartyTraitService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
    public ResponseEntity<BackfillRequest.Receipt> backfill(@RequestBody BackfillRequest body) {
        List<BackfillRequest.TraitRow> items = body.traits() == null ? List.of() : body.traits();
        int written = 0;
        int skipped = 0;
        for (BackfillRequest.TraitRow t : items) {
            if (t.partyId() == null || t.key() == null || t.value() == null || t.value().isBlank()) {
                skipped++;
                continue;
            }
            if (Boolean.TRUE.equals(t.multi())) {
                traits.upsert(t.partyId(), t.key(), t.value());
            } else {
                traits.setTrait(t.partyId(), t.key(), t.value());
            }
            written++;
        }
        return ResponseEntity.ok(new BackfillRequest.Receipt(written, skipped));
    }
}
