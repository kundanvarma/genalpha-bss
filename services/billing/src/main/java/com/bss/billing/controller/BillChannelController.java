package com.bss.billing.controller;

import com.bss.billing.api.ApiConstants;
import com.bss.billing.security.TenantScope;
import com.bss.billing.service.BillChannelService;
import com.bss.billing.service.DirectDebitService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Back-office doors for the channel chain and the direct-debit loop.
 * Consent rows are billing-admin territory (a CSR records what the
 * customer chose); the mandate and settlement FILES keep their batch
 * semantics — the e2e/ops flow fetches them from the rail and posts them
 * here, exactly like a nightly file drop would.
 */
@RestController
@RequestMapping(ApiConstants.BASE_PATH)
public class BillChannelController {

    private final BillChannelService channels;
    private final DirectDebitService directDebit;
    private final TenantScope tenantScope;

    public BillChannelController(BillChannelService channels, DirectDebitService directDebit,
            TenantScope tenantScope) {
        this.channels = channels;
        this.directDebit = directDebit;
        this.tenantScope = tenantScope;
    }

    @GetMapping("/partyBillingChannel")
    public List<Map<String, Object>> listChannels(@RequestParam String partyId) {
        return channels.list(tenantScope.currentTenantId(), partyId);
    }

    @PostMapping("/partyBillingChannel")
    public ResponseEntity<Map<String, Object>> upsertChannel(@RequestBody Map<String, Object> dto) {
        return ResponseEntity.ok(channels.upsert(tenantScope.currentTenantId(), dto));
    }

    @PostMapping("/directDebit/mandateFile")
    public ResponseEntity<Map<String, Object>> mandateFile(@RequestBody Map<String, Object> file) {
        return ResponseEntity.ok(directDebit.ingestMandateFile(tenantScope.currentTenantId(), file));
    }

    @PostMapping("/directDebit/claimRun")
    public ResponseEntity<Map<String, Object>> claimRun() {
        return ResponseEntity.ok(directDebit.claimRun(tenantScope.currentTenantId()));
    }

    @PostMapping(value = "/directDebit/settlementFile",
            consumes = {"text/plain", "application/octet-stream", "application/json"})
    public ResponseEntity<Map<String, Object>> settlementFile(@RequestBody String body) {
        return ResponseEntity.ok(
                directDebit.applySettlementFile(tenantScope.currentTenantId(), body));
    }

    @GetMapping("/directDebit/mandate")
    public List<Map<String, Object>> mandates(@RequestParam(required = false) String partyId) {
        return directDebit.mandateView(tenantScope.currentTenantId(), partyId);
    }

    @GetMapping("/directDebit/claim")
    public List<Map<String, Object>> claims() {
        return directDebit.claimView(tenantScope.currentTenantId());
    }
}
