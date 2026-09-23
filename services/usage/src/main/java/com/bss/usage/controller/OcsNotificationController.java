package com.bss.usage.controller;

import com.bss.usage.dto.PriorityUsageNotification;
import com.bss.usage.dto.PriorityUsageReceipt;
import com.bss.usage.dto.Receipt;
import com.bss.usage.dto.SigscaleRelayReceipt;
import com.bss.usage.dto.SpendThresholdNotification;
import com.bss.usage.dto.SpendVerdict;
import com.bss.usage.dto.UsageThresholdNotification;
import com.bss.usage.service.OcsNotificationAuth;
import com.bss.usage.service.SigscaleNotificationService;
import com.bss.usage.service.UsageService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The OCS → BSS notification door. The Online Charging System calls this when a
 * subscriber crosses a usage threshold ("running low"), when GB ride the
 * priority slice, or when a spend accrual needs a verdict.
 *
 * <p>The caller is a foreign product on the operator's network, so it holds no
 * BSS token: the door is anonymous in the filter chain and <b>HMAC-verified
 * inside</b>, per tenant, exactly as the PSP webhook is
 * ({@link OcsNotificationAuth}). The body arrives as raw bytes because the
 * signature covers those bytes; it is parsed into the notification record only
 * after the signature holds, so a caller still cannot carry a field the record
 * does not declare. Not TMF-facing — hence /internal, kept off the /tmf-api
 * surface and its CTKs.
 */
@RestController
@RequestMapping("/internal/ocs")
public class OcsNotificationController {

    private final UsageService service;
    private final SigscaleNotificationService sigscale;
    private final OcsNotificationAuth auth;

    public OcsNotificationController(UsageService service, SigscaleNotificationService sigscale,
            OcsNotificationAuth auth) {
        this.service = service;
        this.sigscale = sigscale;
        this.auth = auth;
    }

    @PostMapping("/usageThreshold")
    public ResponseEntity<Receipt> usageThreshold(
            @RequestBody(required = false) byte[] body,
            @RequestHeader(value = OcsNotificationAuth.SIGNATURE_HEADER, required = false) String signature) {
        service.notifyUsageThreshold(auth.verified(body, signature, UsageThresholdNotification.class));
        return ResponseEntity.accepted().body(Receipt.ACCEPTED);
    }

    /** SigScale OCS's TMF654 balance hub: the same "running low" truth in
     * SigScale's own event shape (a foreign document), one door per tenant
     * (the hub subscription carries the tenant in its callback). Translated,
     * then relayed like any usage-threshold notification.
     *
     * <p>A TM Forum hub registration is a bare URL with nowhere to put a
     * header, so the credential rides the path: an opaque token derived from
     * this tenant's OCS notification secret, checked before anything is read.
     * A signature header, when the OCS can send one, is honoured too. */
    @PostMapping("/sigscale/{tenantId}/{token}")
    public ResponseEntity<SigscaleRelayReceipt> sigscaleBalance(@PathVariable("tenantId") String tenantId,
            @PathVariable("token") String token,
            @RequestBody(required = false) byte[] body,
            @RequestHeader(value = OcsNotificationAuth.SIGNATURE_HEADER, required = false) String signature) {
        JsonNode parsed = signature == null || signature.isBlank()
                ? auth.acceptCallbackToken(tenantId, token, body)
                : auth.verifiedForTenant(tenantId, body, signature);
        return ResponseEntity.accepted().body(sigscale.accept(tenantId, parsed));
    }

    /** Slice-aware charging: the OCS reports GB that rode the PRIORITY slice; the
     * BSS rates the uplift as its own line ("Priority data") on the next bill. */
    @PostMapping("/priorityUsage")
    public ResponseEntity<PriorityUsageReceipt> priorityUsage(
            @RequestBody(required = false) byte[] body,
            @RequestHeader(value = OcsNotificationAuth.SIGNATURE_HEADER, required = false) String signature) {
        return ResponseEntity.accepted()
                .body(service.recordPriorityUsage(auth.verified(body, signature, PriorityUsageNotification.class)));
    }

    /** The monetary sibling: an external charging edge reports a spend
     * accrual {partyId, chargeClass, amount}; the reply's accepted=false
     * tells it to refuse the charge (barring, cap, roaming cut-off). */
    @PostMapping("/spendThreshold")
    public ResponseEntity<SpendVerdict> spendThreshold(
            @RequestBody(required = false) byte[] body,
            @RequestHeader(value = OcsNotificationAuth.SIGNATURE_HEADER, required = false) String signature) {
        return ResponseEntity.accepted()
                .body(service.notifySpendThreshold(auth.verified(body, signature, SpendThresholdNotification.class)));
    }
}
