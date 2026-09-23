package com.bss.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The ingress a foreign BSS posts its own events to. In production the source
 * would authenticate (mTLS / a shared secret / its own OIDC) and the bridge
 * would also be able to SUBSCRIBE to the source's TMF688 Hub instead of being
 * pushed to. Kept open here — it is an internal-network adapter.
 */
@RestController
public class BridgeController {

    private final BridgeService service;

    public BridgeController(BridgeService service) {
        this.service = service;
    }

    @PostMapping("/bridge/v1/{source}/event")
    public ResponseEntity<BridgeReceipt> event(@PathVariable String source,
            @RequestBody JsonNode foreignEvent) {
        return ResponseEntity.ok(service.ingest(source, foreignEvent));
    }
}
