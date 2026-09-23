package com.bss.paymentmethod.controller;

import com.bss.paymentmethod.api.ApiConstants;
import com.bss.paymentmethod.dto.PaymentMethodRequest;
import com.bss.paymentmethod.dto.PaymentMethodView;
import com.bss.paymentmethod.service.PaymentMethodService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping(ApiConstants.BASE_PATH + "/paymentMethod")
public class PaymentMethodController {

    private final PaymentMethodService service;

    public PaymentMethodController(PaymentMethodService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<PaymentMethodView> create(@RequestBody PaymentMethodRequest dto) {
        PaymentMethodView created = service.create(dto);
        return ResponseEntity.created(URI.create(created.href())).body(created);
    }

    @GetMapping
    public ResponseEntity<List<PaymentMethodView>> mine(
            @RequestParam(name = "relatedPartyId", required = false) String relatedPartyId) {
        return ResponseEntity.ok(service.mine(relatedPartyId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PaymentMethodView> resolve(@PathVariable String id) {
        return ResponseEntity.ok(service.resolve(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
