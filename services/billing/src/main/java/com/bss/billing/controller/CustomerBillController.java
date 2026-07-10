package com.bss.billing.controller;

import com.bss.billing.api.ApiConstants;
import com.bss.billing.api.PagedResult;
import com.bss.billing.dto.CustomerBillDto;
import com.bss.billing.service.CustomerBillService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    public CustomerBillController(CustomerBillService service) {
        this.service = service;
    }

    @GetMapping("/customerBill")
    public ResponseEntity<List<CustomerBillDto>> list(
            @RequestParam(name = "offset", defaultValue = "0") @Min(0) int offset,
            @RequestParam(name = "limit", defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam Map<String, String> allParams) {
        Map<String, String> filters = new HashMap<>(allParams);
        filters.remove("offset");
        filters.remove("limit");
        PagedResult<CustomerBillDto> result = service.findAll(offset, limit, filters);
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(result.totalCount()))
                .header("X-Result-Count", String.valueOf(result.items().size()))
                .body(result.items());
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
}
