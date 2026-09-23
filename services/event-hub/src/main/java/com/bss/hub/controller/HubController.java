package com.bss.hub.controller;

import com.bss.hub.api.ApiConstants;
import com.bss.hub.api.DeliveryView;
import com.bss.hub.api.HubRequest;
import com.bss.hub.api.HubView;
import com.bss.hub.service.HubService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** TMF688's face: /hub registration, and the ledger behind each listener. */
@RestController
@RequestMapping(ApiConstants.BASE_PATH)
public class HubController {

    private final HubService service;

    public HubController(HubService service) {
        this.service = service;
    }

    @PostMapping("/hub")
    public ResponseEntity<HubView> register(@RequestBody HubRequest dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.register(dto));
    }

    @GetMapping("/hub")
    public ResponseEntity<List<HubView>> list() {
        return ResponseEntity.ok(service.list());
    }

    @DeleteMapping("/hub/{id}")
    public ResponseEntity<Void> unregister(@PathVariable("id") String id) {
        service.unregister(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/hub/{id}/delivery")
    public ResponseEntity<List<DeliveryView>> deliveries(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.deliveriesOf(id));
    }
}
