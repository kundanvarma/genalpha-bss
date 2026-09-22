package com.bss.insight.controller;

import com.bss.insight.api.ApiConstants;
import com.bss.insight.dto.ConnectorSyncReceipt;
import com.bss.insight.dto.SignalConnectorRequest;
import com.bss.insight.dto.SignalConnectorView;
import com.bss.insight.dto.SignalView;
import com.bss.insight.service.SignalConnectorService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The operator's signal connectors (SI-P2): CRUD + poll-mode sync are
 * back-office; the webhook door is anonymous at the gateway and opens only
 * to the connector's shared secret (X-Hook-Secret), verified inside.
 */
@RestController
public class SignalConnectorController {

    private final SignalConnectorService service;

    public SignalConnectorController(SignalConnectorService service) {
        this.service = service;
    }

    @GetMapping(ApiConstants.BASE_PATH + "/connector")
    public ResponseEntity<List<SignalConnectorView>> list() {
        return ResponseEntity.ok(service.list());
    }

    @PutMapping(ApiConstants.BASE_PATH + "/connector")
    public ResponseEntity<SignalConnectorView> upsert(@RequestBody SignalConnectorRequest dto) {
        return ResponseEntity.ok(service.upsert(dto));
    }

    @DeleteMapping(ApiConstants.BASE_PATH + "/connector/{name}")
    public ResponseEntity<Void> delete(@PathVariable String name) {
        service.delete(name);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(ApiConstants.BASE_PATH + "/connector/{name}/sync")
    public ResponseEntity<ConnectorSyncReceipt> sync(@PathVariable String name) {
        return ResponseEntity.ok(service.sync(name));
    }

    /** The generic inbound webhook — a foreign system pushes its own shape. */
    @PostMapping(ApiConstants.BASE_PATH + "/hook/{connectorId}")
    public ResponseEntity<SignalView> hook(@PathVariable String connectorId,
            @RequestHeader(value = "X-Hook-Secret", required = false) String secret,
            @RequestBody String rawBody) {
        return ResponseEntity.status(201).body(service.webhook(connectorId, secret, rawBody));
    }
}
