package com.bss.som.controller;

import com.bss.som.dto.Characteristic;
import com.bss.som.dto.WholesaleDtos.ProviderAccessOrderView;
import com.bss.som.dto.WholesaleDtos.ProviderSettlement;
import com.bss.som.dto.WholesaleDtos.RetailerStatement;
import com.bss.som.dto.WholesaleDtos.SonataOrderAck;
import com.bss.som.dto.WholesaleDtos.SonataOrderRequest;
import com.bss.som.entity.ProviderAccessOrder;
import com.bss.som.security.TenantScope;
import com.bss.som.security.WholesaleDoorAuth;
import com.bss.som.service.WholesaleProviderService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Our MEF LSO Sonata Service Ordering face, as a fibre OWNER: retailers place
 * access-seeker orders here. Anonymous in the filter chain — the retailer is an
 * external operator's BSS, not a fleet identity — and signed inside: the body
 * carries an HMAC over itself made with the wholesale secret of the operator the
 * X-Tenant-Id header names ({@link WholesaleDoorAuth}). That is what makes the
 * header safe to believe: before, any caller on the private network could pick a
 * tenant, name any buyerId as the party to wholesale-bill, and hand us an
 * arbitrary callback URL to POST to. We record the order, provision it on our own
 * clock, and notify their callback.
 */
@RestController
public class WholesaleProviderController {

    private final WholesaleProviderService provider;
    private final WholesaleDoorAuth auth;
    private final TenantScope tenantScope;

    public WholesaleProviderController(WholesaleProviderService provider, WholesaleDoorAuth auth,
            TenantScope tenantScope) {
        this.provider = provider;
        this.auth = auth;
        this.tenantScope = tenantScope;
    }

    @PostMapping("/mefApi/serviceOrdering/v1/serviceOrder")
    public ResponseEntity<SonataOrderAck> order(
            @RequestBody(required = false) byte[] raw,
            @RequestHeader(value = WholesaleDoorAuth.SIGNATURE_HEADER, required = false) String signature) {
        // the signature is verified against the named tenant's own secret, so the
        // body is parsed into the record only once the seeker has proved itself
        SonataOrderRequest body = auth.verifiedOrder(tenantScope.currentTenantId(), raw, signature,
                SonataOrderRequest.class);
        String accessLayer = null, postCode = null;
        Integer bandwidth = null;
        for (Characteristic ch : body.characteristics()) {
            if (ch == null) {
                continue;
            }
            if ("accessLayer".equals(ch.name())) {
                accessLayer = ch.text();
            } else if ("postCode".equals(ch.name())) {
                postCode = ch.text();
            } else if ("bandwidthMbps".equals(ch.name()) && ch.value() instanceof Number n) {
                bandwidth = n.intValue();
            }
        }
        ProviderAccessOrder o = provider.acceptOrder(body.externalId(), body.callbackUrl(), body.buyerId(),
                accessLayer, bandwidth, postCode);
        return ResponseEntity.status(201).body(new SonataOrderAck(o.getId(), o.getState(), "ServiceOrder"));
    }

    /** What we have sold — the provider's order book (for the partner portal + billing). */
    @GetMapping("/tmf-api/serviceOrdering/v4/providerAccessOrder")
    public ResponseEntity<List<ProviderAccessOrderView>> list() {
        return ResponseEntity.ok(provider.list().stream().map(this::view).toList());
    }

    /** What each retailer owes US for the access live on our network — the wholesale
     *  bill we raise as the fibre owner (accounts receivable). */
    @GetMapping("/tmf-api/serviceOrdering/v4/wholesaleProviderSettlement")
    public ResponseEntity<ProviderSettlement> settlement() {
        List<RetailerStatement> retailers = provider.providerSettlement();
        double total = retailers.stream().mapToDouble(RetailerStatement::totalMonthlyCharge).sum();
        return ResponseEntity.ok(new ProviderSettlement("WholesaleProviderSettlement", "month", retailers,
                Math.round(total * 100.0) / 100.0, "EUR"));
    }

    private ProviderAccessOrderView view(ProviderAccessOrder o) {
        return new ProviderAccessOrderView(o.getId(), o.getBuyerRef(), o.getRetailerPartyId(), o.getAccessLayer(),
                o.getBandwidthMbps(), o.getPostCode(), o.getState(), o.getActivatedAt(), o.getCreatedAt(),
                "ProviderAccessOrder");
    }
}
