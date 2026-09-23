package com.bss.som.controller;

import com.bss.som.dto.Characteristic;
import com.bss.som.dto.WholesaleDtos.ProviderAccessOrderView;
import com.bss.som.dto.WholesaleDtos.ProviderSettlement;
import com.bss.som.dto.WholesaleDtos.RetailerStatement;
import com.bss.som.dto.WholesaleDtos.SonataOrderAck;
import com.bss.som.dto.WholesaleDtos.SonataOrderRequest;
import com.bss.som.entity.ProviderAccessOrder;
import com.bss.som.service.WholesaleProviderService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Our MEF LSO Sonata Service Ordering face, as a fibre OWNER: retailers place
 * access-seeker orders here. Anonymous — the retailer is an external operator, not a
 * fleet identity; the tenant is carried on X-Tenant-Id (resolved and validated by
 * TenantScope, so a caller can only reach a tenant that exists). We record the
 * order, provision it on our own clock, and notify their callback.
 */
@RestController
public class WholesaleProviderController {

    private final WholesaleProviderService provider;

    public WholesaleProviderController(WholesaleProviderService provider) {
        this.provider = provider;
    }

    @PostMapping("/mefApi/serviceOrdering/v1/serviceOrder")
    public ResponseEntity<SonataOrderAck> order(@RequestBody SonataOrderRequest body) {
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
