package com.bss.entitlement.controller;

import com.bss.entitlement.api.ApiConstants;
import com.bss.entitlement.dto.CompanionView;
import com.bss.entitlement.dto.DeviceView;
import com.bss.entitlement.dto.EcsRequestView;
import com.bss.entitlement.dto.ReconfigureReceipt;
import com.bss.entitlement.dto.ReconfigureRequest;
import com.bss.entitlement.dto.RevokeReceipt;
import com.bss.entitlement.dto.SubscriberDetail;
import com.bss.entitlement.dto.SubscriberUpsertRequest;
import com.bss.entitlement.dto.SubscriberView;
import com.bss.entitlement.service.SubscriberService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The BSS face (house API, TMF-styled): subscriber bindings, what a line is
 * entitled to in plain words and in TS.43 terms, the devices that checked
 * in, companion eSIMs, the request log, and server-initiated re-configuration.
 */
@RestController
@RequestMapping(ApiConstants.BASE_PATH)
public class EntitlementAdminController {

    private final SubscriberService subscribers;

    public EntitlementAdminController(SubscriberService subscribers) {
        this.subscribers = subscribers;
    }

    @PutMapping("/subscriber")
    public ResponseEntity<SubscriberView> upsert(@RequestBody SubscriberUpsertRequest dto) {
        return ResponseEntity.status(HttpStatus.OK).body(subscribers.upsert(dto));
    }

    @GetMapping("/subscriber")
    public List<SubscriberView> list(@RequestParam(required = false) String imsi,
            @RequestParam(required = false) String partyId,
            @RequestParam(required = false) String serviceId) {
        return subscribers.list(imsi, partyId, serviceId);
    }

    @GetMapping("/subscriber/{imsi}")
    public SubscriberDetail get(@PathVariable("imsi") String imsi) {
        return subscribers.entitlements(imsi);
    }

    @GetMapping("/subscriber/{imsi}/entitlement")
    public SubscriberDetail entitlements(@PathVariable("imsi") String imsi) {
        return subscribers.entitlements(imsi);
    }

    @DeleteMapping("/subscriber/{imsi}")
    public ResponseEntity<Void> unbind(@PathVariable("imsi") String imsi) {
        subscribers.delete(imsi);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/subscriber/{imsi}/reconfigure")
    public ReconfigureReceipt reconfigure(@PathVariable("imsi") String imsi,
            @RequestBody(required = false) ReconfigureRequest body) {
        return subscribers.reconfigure(imsi,
                (body == null ? ReconfigureRequest.EMPTY : body).appsOrDefault());
    }

    @PostMapping("/subscriber/{imsi}/revokeTokens")
    public RevokeReceipt revoke(@PathVariable("imsi") String imsi) {
        return subscribers.revokeTokens(imsi);
    }

    @GetMapping("/device")
    public List<DeviceView> devices() {
        return subscribers.devices();
    }

    @GetMapping("/companionDevice")
    public List<CompanionView> companions() {
        return subscribers.companions();
    }

    @GetMapping("/ecsRequest")
    public List<EcsRequestView> requests(@RequestParam(required = false) String imsi) {
        return subscribers.requests(imsi);
    }
}
