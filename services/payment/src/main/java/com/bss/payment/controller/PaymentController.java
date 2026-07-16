package com.bss.payment.controller;

import com.bss.payment.api.ApiConstants;
import com.bss.payment.api.FieldSelector;
import com.bss.payment.api.PagedResult;
import com.bss.payment.dto.PaymentDto;
import com.bss.payment.service.PaymentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** No DELETE: payments are financial records — they void, they do not vanish. */
@RestController
@Validated
@RequestMapping(ApiConstants.BASE_PATH + "/payment")
public class PaymentController {

    private final PaymentService service;
    private final FieldSelector fieldSelector;

    public PaymentController(PaymentService service, FieldSelector fieldSelector) {
        this.service = service;
        this.fieldSelector = fieldSelector;
    }

    @GetMapping
    public ResponseEntity<List<?>> list(
            @RequestParam(name = "offset", defaultValue = "0") @Min(0) int offset,
            @RequestParam(name = "limit", defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(name = "fields", required = false) String fields,
            @RequestParam Map<String, String> allParams) {
        Map<String, String> filters = new HashMap<>(allParams);
        filters.remove("offset");
        filters.remove("limit");
        filters.remove("fields");
        PagedResult<PaymentDto> result = service.findAll(offset, limit, filters);
        List<?> body = fields == null ? result.items() : fieldSelector.select(result.items(), fields);
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(result.totalCount()))
                .header("X-Result-Count", String.valueOf(result.items().size()))
                .body(body);
    }

    /** Money that arrived AT THE BANK (giro/credit transfer) — recorded by
     * remittance ingestion, machine-to-machine. */
    @PostMapping("/external")
    public ResponseEntity<PaymentDto> recordExternal(@RequestBody Map<String, Object> dto) {
        PaymentDto created = service.recordExternal(dto);
        return ResponseEntity.created(URI.create(created.getHref())).body(created);
    }

    @GetMapping("/{id}")
    public ResponseEntity<PaymentDto> getById(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.findById(id));
    }

    @PostMapping
    public ResponseEntity<PaymentDto> create(@Valid @RequestBody PaymentDto dto) {
        PaymentDto created = service.create(dto);
        return ResponseEntity
                .created(URI.create(created.getHref()))
                .body(created);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<PaymentDto> patch(@PathVariable("id") String id,
                                            @RequestBody PaymentDto patch) {
        return ResponseEntity.ok(service.patch(id, patch));
    }

    /** Money BACK, partial or full — the PSP confirms before the record moves. */
    @PostMapping("/{id}/refund")
    public ResponseEntity<java.util.Map<String, Object>> refund(
            @PathVariable("id") String id,
            @RequestBody(required = false) java.util.Map<String, Object> dto) {
        return ResponseEntity.ok(service.refund(id, dto == null ? java.util.Map.of() : dto));
    }
}
