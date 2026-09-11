package com.bss.entitlement.controller;

import com.bss.entitlement.api.ApiConstants;
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
import java.util.Map;

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
    public ResponseEntity<Map<String, Object>> upsert(@RequestBody Map<String, Object> dto) {
        return ResponseEntity.status(HttpStatus.OK).body(subscribers.upsert(dto));
    }

    @GetMapping("/subscriber")
    public List<Map<String, Object>> list(@RequestParam(required = false) String imsi,
            @RequestParam(required = false) String partyId,
            @RequestParam(required = false) String serviceId) {
        return subscribers.list(imsi, partyId, serviceId);
    }

    @GetMapping("/subscriber/{imsi}")
    public Map<String, Object> get(@PathVariable("imsi") String imsi) {
        return subscribers.entitlements(imsi);
    }

    @GetMapping("/subscriber/{imsi}/entitlement")
    public Map<String, Object> entitlements(@PathVariable("imsi") String imsi) {
        return subscribers.entitlements(imsi);
    }

    @DeleteMapping("/subscriber/{imsi}")
    public ResponseEntity<Void> unbind(@PathVariable("imsi") String imsi) {
        subscribers.delete(imsi);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/subscriber/{imsi}/reconfigure")
    public Map<String, Object> reconfigure(@PathVariable("imsi") String imsi,
            @RequestBody(required = false) Map<String, Object> body) {
        List<String> apps = body != null && body.get("apps") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : List.of("ap2003", "ap2004", "ap2005");
        return subscribers.reconfigure(imsi, apps);
    }

    @PostMapping("/subscriber/{imsi}/revokeTokens")
    public Map<String, Object> revoke(@PathVariable("imsi") String imsi) {
        return subscribers.revokeTokens(imsi);
    }

    @GetMapping("/device")
    public List<Map<String, Object>> devices() {
        return subscribers.devices();
    }

    @GetMapping("/companionDevice")
    public List<Map<String, Object>> companions() {
        return subscribers.companions();
    }

    @GetMapping("/ecsRequest")
    public List<Map<String, Object>> requests(@RequestParam(required = false) String imsi) {
        return subscribers.requests(imsi);
    }
}
