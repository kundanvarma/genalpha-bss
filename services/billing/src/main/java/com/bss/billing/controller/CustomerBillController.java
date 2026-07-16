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
    private final com.bss.billing.service.DunningService dunningService;
    private final com.bss.billing.service.DisputeService disputeService;
    private final com.bss.billing.service.BillFormatProfileService formatProfileService;
    private final com.bss.billing.service.BillDistributionService distributionService;
    private final com.bss.billing.service.RemittanceService remittanceService;
    private final com.bss.billing.service.BillDocumentService documentService;
    private final com.bss.billing.security.TenantScope tenantScope;

    public CustomerBillController(CustomerBillService service, FieldSelector fieldSelector,
            com.bss.billing.service.DunningService dunningService,
            com.bss.billing.service.DisputeService disputeService,
            com.bss.billing.service.BillFormatProfileService formatProfileService,
            com.bss.billing.service.BillDistributionService distributionService,
            com.bss.billing.service.RemittanceService remittanceService,
            com.bss.billing.security.TenantScope tenantScope,
            com.bss.billing.service.BillDocumentService documentService) {
        this.service = service;
        this.fieldSelector = fieldSelector;
        this.dunningService = dunningService;
        this.disputeService = disputeService;
        this.formatProfileService = formatProfileService;
        this.distributionService = distributionService;
        this.remittanceService = remittanceService;
        this.documentService = documentService;
        this.tenantScope = tenantScope;
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

    /** The bill as a PDF a person can save, print or forward. */
    @GetMapping(value = "/customerBill/{id}/document.pdf",
            produces = org.springframework.http.MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(@PathVariable("id") String id) {
        return ResponseEntity.ok()
                .header("Content-Disposition", "inline; filename=\"" + id + ".pdf\"")
                .body(documentService.pdfOf(id));
    }

    /** "Send me a copy of my invoice" — emails the PDF to the address on
     * file, from the CSR console or self-served. */
    @PostMapping("/customerBill/{id}/resend")
    public ResponseEntity<Map<String, Object>> resend(@PathVariable("id") String id) {
        return ResponseEntity.accepted().body(documentService.resend(id));
    }

    /** "This charge is wrong": open a dispute (customer or agent). */
    @PostMapping("/customerBill/{id}/dispute")
    public ResponseEntity<Map<String, Object>> dispute(@PathVariable("id") String id,
            @RequestBody Map<String, Object> dto) {
        return ResponseEntity.ok(disputeService.open(id, dto));
    }

    /** FORMAT PROFILES AS CONFIG ROWS: what each e-invoice profile is —
     * readable by anyone with billing read, editable by the tenant admin.
     * Adding a country is a POST here, not a deploy. */
    @GetMapping("/billFormatProfile")
    public ResponseEntity<java.util.List<Map<String, Object>>> formatProfiles() {
        return ResponseEntity.ok(formatProfileService.findAll());
    }

    @GetMapping("/billFormatProfile/{code}")
    public ResponseEntity<Map<String, Object>> formatProfile(@PathVariable("code") String code) {
        return ResponseEntity.ok(formatProfileService.findByCode(code));
    }

    @PatchMapping("/billFormatProfile/{code}")
    public ResponseEntity<Map<String, Object>> upsertFormatProfile(
            @PathVariable("code") String code, @RequestBody Map<String, Object> dto) {
        return ResponseEntity.ok(formatProfileService.upsert(code, dto));
    }

    /** Adding a country IS a create here — a row, not a deploy. */
    @PostMapping("/billFormatProfile")
    public ResponseEntity<Map<String, Object>> createFormatProfile(
            @RequestBody Map<String, Object> dto) {
        if (dto.get("code") == null || String.valueOf(dto.get("code")).isBlank()) {
            throw new com.bss.billing.exception.BadRequestException(
                    "a profile needs a code — the key the tenant's distribution format points at");
        }
        Map<String, Object> created = formatProfileService.upsert(
                String.valueOf(dto.get("code")), dto);
        return ResponseEntity.created(URI.create(ApiConstants.BASE_PATH
                + "/billFormatProfile/" + created.get("code"))).body(created);
    }

    /** UNAPPLIED CASH: money the bank reported that no bill cleanly
     * claims — the AR worklist a human resolves. */
    @GetMapping("/remittance/unapplied")
    public ResponseEntity<java.util.List<Map<String, Object>>> unappliedCash() {
        return ResponseEntity.ok(remittanceService.unappliedView(tenantScope.currentTenantId()));
    }

    /** THE DELIVERY LEDGER: what left for the distribution partner, when,
     * after how many tries — and what is still owed a retry. */
    @GetMapping("/billDistribution")
    public ResponseEntity<java.util.List<Map<String, Object>>> distributionLedger(
            @RequestParam(name = "status", required = false) String status) {
        return ResponseEntity.ok(distributionService.ledgerView(tenantScope.currentTenantId(), status));
    }

    /** An admin's second chance for a FAILED delivery. */
    @PostMapping("/billDistribution/{id}/retry")
    public ResponseEntity<Map<String, Object>> retryDistribution(@PathVariable("id") String id) {
        return ResponseEntity.accepted()
                .body(distributionService.retry(tenantScope.currentTenantId(), id));
    }

    /** The disputes worklist (staff). */
    @GetMapping("/dispute")
    public ResponseEntity<List<Map<String, Object>>> disputes() {
        return ResponseEntity.ok(disputeService.list());
    }

    /** The decision: credit an amount, or uphold with the reason. */
    @PostMapping("/dispute/{id}/resolve")
    public ResponseEntity<Map<String, Object>> resolve(@PathVariable("id") String id,
            @RequestBody Map<String, Object> dto) {
        return ResponseEntity.ok(disputeService.resolve(id, dto));
    }

    /** The DUNNING window: who is overdue, who broke, what is still owed. */
    @GetMapping("/dunning")
    public ResponseEntity<List<Map<String, Object>>> dunning() {
        return ResponseEntity.ok(dunningService.dunningView(tenantScope.currentTenantId()));
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
