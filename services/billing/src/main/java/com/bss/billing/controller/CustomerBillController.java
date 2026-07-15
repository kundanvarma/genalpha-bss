package com.bss.billing.controller;

import com.bss.billing.api.ApiConstants;
import com.bss.billing.api.FieldSelector;
import com.bss.billing.api.PagedResult;
import com.bss.billing.dto.CustomerBillDto;
import com.bss.billing.service.CustomerBillService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
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

/**
 * Bills are cut by the billing run, never POSTed; the only PATCH is settling
 * with a payment. No DELETE — bills are financial records.
 */
@RestController
@Validated
@RequestMapping(ApiConstants.BASE_PATH)
public class CustomerBillController {

    private final CustomerBillService service;
    private final FieldSelector fieldSelector;

    public CustomerBillController(CustomerBillService service, FieldSelector fieldSelector) {
        this.service = service;
        this.fieldSelector = fieldSelector;
    }

    @GetMapping("/customerBill")
    public ResponseEntity<List<?>> list(
            @RequestParam(name = "offset", defaultValue = "0") @Min(0) int offset,
            @RequestParam(name = "limit", defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(name = "fields", required = false) String fields,
            @RequestParam Map<String, String> allParams) {
        Map<String, String> filters = new HashMap<>(allParams);
        filters.keySet().removeAll(List.of("offset", "limit", "fields"));
        PagedResult<CustomerBillDto> result = service.findAll(offset, limit, filters);
        List<?> body = fields == null ? result.items() : fieldSelector.select(result.items(), fields);
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(result.totalCount()))
                .header("X-Result-Count", String.valueOf(result.items().size()))
                .body(body);
    }

    // ---- TMF678 CustomerBillOnDemand ----

    @PostMapping("/customerBillOnDemand")
    public ResponseEntity<Map<String, Object>> createOnDemand(@RequestBody Map<String, Object> body) {
        Map<String, Object> created = service.createOnDemand(body);
        return ResponseEntity.created(URI.create(String.valueOf(created.get("href")))).body(created);
    }

    @GetMapping("/customerBillOnDemand")
    public ResponseEntity<List<?>> listOnDemand(
            @RequestParam(name = "fields", required = false) String fields,
            @RequestParam Map<String, String> allParams) {
        List<Map<String, Object>> items = service.findOnDemand(clean(allParams));
        return ResponseEntity.ok(fields == null ? items : fieldSelector.select(items, fields));
    }

    @GetMapping("/customerBillOnDemand/{id}")
    public ResponseEntity<Map<String, Object>> getOnDemand(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.findOnDemandById(id));
    }

    // ---- TMF678 top-level appliedCustomerBillingRate ----

    @GetMapping("/appliedCustomerBillingRate")
    public ResponseEntity<List<?>> listRates(
            @RequestParam(name = "fields", required = false) String fields,
            @RequestParam Map<String, String> allParams) {
        List<Map<String, Object>> items = service.findAllRates(clean(allParams));
        return ResponseEntity.ok(fields == null ? items : fieldSelector.select(items, fields));
    }

    @GetMapping("/appliedCustomerBillingRate/{id}")
    public ResponseEntity<Map<String, Object>> getRate(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.findRateById(id));
    }

    private static Map<String, String> clean(Map<String, String> allParams) {
        Map<String, String> f = new HashMap<>(allParams);
        f.keySet().removeAll(List.of("offset", "limit", "fields", "sort"));
        return f;
    }

    @GetMapping("/customerBill/{id}")
    public ResponseEntity<CustomerBillDto> getById(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.findById(id));
    }

    /** TMF678 appliedCustomerBillingRate, scoped through its bill. */
    @GetMapping("/customerBill/{id}/appliedCustomerBillingRate")
    public ResponseEntity<List<Map<String, Object>>> rates(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.ratesOf(id));
    }

    @PatchMapping("/customerBill/{id}")
    public ResponseEntity<CustomerBillDto> settle(@PathVariable("id") String id,
                                                  @RequestBody CustomerBillDto patch) {
        return ResponseEntity.ok(service.settle(id, patch));
    }

    /** PAY IN PARTS: split an unpaid bill into 2-12 monthly installments. */
    @PostMapping("/customerBill/{id}/installmentPlan")
    public ResponseEntity<Map<String, Object>> installmentPlan(@PathVariable("id") String id,
            @RequestBody(required = false) Map<String, Object> dto) {
        return ResponseEntity.ok(service.createInstallmentPlan(id, dto == null ? Map.of() : dto));
    }

    /** One part lands: an authorized payment covering THIS installment. */
    @PostMapping("/customerBill/{id}/installmentPlan/pay")
    public ResponseEntity<Map<String, Object>> payInstallment(@PathVariable("id") String id,
            @RequestBody Map<String, Object> dto) {
        return ResponseEntity.ok(service.payInstallment(id, dto));
    }
}
