package com.bss.billing.controller;

import com.bss.billing.api.ApiConstants;
import com.bss.billing.api.FieldSelector;
import com.bss.billing.api.PagedResult;
import com.bss.billing.dto.AppliedBillingRateView;
import com.bss.billing.dto.ApplyUnappliedRequest;
import com.bss.billing.dto.BillFormatProfileRequest;
import com.bss.billing.dto.BillFormatProfileView;
import com.bss.billing.dto.CreditNoteRequest;
import com.bss.billing.dto.CreditNoteView;
import com.bss.billing.dto.CustomerBillDto;
import com.bss.billing.dto.DisputeRequest;
import com.bss.billing.dto.DisputeResolution;
import com.bss.billing.dto.DisputeView;
import com.bss.billing.dto.DistributionDtos.LedgerRow;
import com.bss.billing.dto.DistributionDtos.RetryReceipt;
import com.bss.billing.dto.DunningRow;
import com.bss.billing.dto.InstallmentPaymentRequest;
import com.bss.billing.dto.InstallmentPlanRequest;
import com.bss.billing.dto.InstallmentPlanView;
import com.bss.billing.dto.RemittanceApplied;
import com.bss.billing.dto.ResendReceipt;
import com.bss.billing.dto.UnappliedRemittanceView;
import com.bss.billing.service.CustomerBillService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
    private final com.bss.billing.service.CreditNoteService creditNoteService;
    private final com.bss.billing.service.BillFormatProfileService formatProfileService;
    private final com.bss.billing.service.BillDistributionService distributionService;
    private final com.bss.billing.service.RemittanceService remittanceService;
    private final com.bss.billing.service.BillDocumentService documentService;
    private final com.bss.billing.service.CollectionService collectionService;
    private final com.bss.billing.security.TenantScope tenantScope;

    public CustomerBillController(CustomerBillService service, FieldSelector fieldSelector,
            com.bss.billing.service.DunningService dunningService,
            com.bss.billing.service.DisputeService disputeService,
            com.bss.billing.service.CreditNoteService creditNoteService,
            com.bss.billing.service.BillFormatProfileService formatProfileService,
            com.bss.billing.service.BillDistributionService distributionService,
            com.bss.billing.service.RemittanceService remittanceService,
            com.bss.billing.security.TenantScope tenantScope,
            com.bss.billing.service.CollectionService collectionService,
            com.bss.billing.service.BillDocumentService documentService) {
        this.service = service;
        this.fieldSelector = fieldSelector;
        this.dunningService = dunningService;
        this.disputeService = disputeService;
        this.creditNoteService = creditNoteService;
        this.formatProfileService = formatProfileService;
        this.distributionService = distributionService;
        this.remittanceService = remittanceService;
        this.documentService = documentService;
        this.collectionService = collectionService;
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

    /**
     * The finance desk's list, by what is TRUE about a bill rather than by
     * its stored state: {@code ?situation=overdue} and the rest of the
     * vocabulary. A house resource beside the standard one — the TMF list
     * stays exactly as conformance expects it.
     */
    @GetMapping("/billSituation")
    public ResponseEntity<List<CustomerBillDto>> bySituation(
            @RequestParam(name = "offset", defaultValue = "0") @Min(0) int offset,
            @RequestParam(name = "limit", defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(name = "situation", required = false) String situation) {
        PagedResult<CustomerBillDto> result = service.findBySituation(offset, limit, situation);
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(result.totalCount()))
                .header("X-Result-Count", String.valueOf(result.items().size()))
                .body(result.items());
    }

    // ---- TMF678 CustomerBillOnDemand ----

    /** The caller's document, kept verbatim: an open tree in, the same tree with server fields out. */
    @PostMapping("/customerBillOnDemand")
    public ResponseEntity<ObjectNode> createOnDemand(@RequestBody JsonNode body) {
        ObjectNode created = service.createOnDemand(body);
        return ResponseEntity.created(URI.create(created.path("href").asText())).body(created);
    }

    @GetMapping("/customerBillOnDemand")
    public ResponseEntity<List<?>> listOnDemand(
            @RequestParam(name = "fields", required = false) String fields,
            @RequestParam Map<String, String> allParams) {
        List<ObjectNode> items = service.findOnDemand(clean(allParams));
        return ResponseEntity.ok(fields == null ? items : fieldSelector.select(items, fields));
    }

    @GetMapping("/customerBillOnDemand/{id}")
    public ResponseEntity<ObjectNode> getOnDemand(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.findOnDemandById(id));
    }

    // ---- TMF678 top-level appliedCustomerBillingRate ----

    @GetMapping("/appliedCustomerBillingRate")
    public ResponseEntity<List<?>> listRates(
            @RequestParam(name = "fields", required = false) String fields,
            @RequestParam Map<String, String> allParams) {
        List<AppliedBillingRateView> items = service.findAllRates(clean(allParams));
        return ResponseEntity.ok(fields == null ? items : fieldSelector.select(items, fields));
    }

    @GetMapping("/appliedCustomerBillingRate/{id}")
    public ResponseEntity<AppliedBillingRateView> getRate(@PathVariable("id") String id) {
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
    public ResponseEntity<List<AppliedBillingRateView>> rates(@PathVariable("id") String id) {
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
    public ResponseEntity<ResendReceipt> resend(@PathVariable("id") String id) {
        return ResponseEntity.accepted().body(documentService.resend(id));
    }

    /** The reversing DOCUMENT: numbered, gapless, reason required.
     * Unpaid bill: the due comes down. Settled: the PSP pays it back. */
    @PostMapping("/customerBill/{id}/creditNote")
    public ResponseEntity<CreditNoteView> issueCreditNote(@PathVariable("id") String id,
            @RequestBody CreditNoteRequest dto) {
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED)
                .body(creditNoteService.issue(id, dto));
    }

    @GetMapping("/creditNote")
    public ResponseEntity<List<CreditNoteView>> creditNotes(
            @RequestParam(name = "billId", required = false) String billId,
            @RequestParam(name = "relatedPartyId", required = false) String relatedPartyId) {
        return ResponseEntity.ok(creditNoteService.list(billId, relatedPartyId));
    }

    @GetMapping("/creditNote/{id}")
    public ResponseEntity<CreditNoteView> creditNote(@PathVariable("id") String id) {
        return ResponseEntity.ok(creditNoteService.byId(id));
    }

    /** The legal wire format: UBL CreditNote-2 (EHF / Peppol BIS shape). */
    @GetMapping(value = "/creditNote/{id}/document.xml", produces = "application/xml")
    public ResponseEntity<String> creditNoteXml(@PathVariable("id") String id) {
        return ResponseEntity.ok(creditNoteService.xmlOf(id));
    }

    @GetMapping(value = "/creditNote/{id}/document.pdf", produces = "application/pdf")
    public ResponseEntity<byte[]> creditNotePdf(@PathVariable("id") String id) {
        return ResponseEntity.ok()
                .header("Content-Disposition", "inline; filename=\"" + id + ".pdf\"")
                .body(creditNoteService.pdfOf(id));
    }

    /** "This charge is wrong": open a dispute (customer or agent). */
    @PostMapping("/customerBill/{id}/dispute")
    public ResponseEntity<DisputeView> dispute(@PathVariable("id") String id,
            @RequestBody DisputeRequest dto) {
        return ResponseEntity.ok(disputeService.open(id, dto));
    }

    /** FORMAT PROFILES AS CONFIG ROWS: what each e-invoice profile is —
     * readable by anyone with billing read, editable by the tenant admin.
     * Adding a country is a POST here, not a deploy. */
    @GetMapping("/billFormatProfile")
    public ResponseEntity<List<BillFormatProfileView>> formatProfiles() {
        return ResponseEntity.ok(formatProfileService.findAll());
    }

    @GetMapping("/billFormatProfile/{code}")
    public ResponseEntity<BillFormatProfileView> formatProfile(@PathVariable("code") String code) {
        return ResponseEntity.ok(formatProfileService.findByCode(code));
    }

    @PatchMapping("/billFormatProfile/{code}")
    public ResponseEntity<BillFormatProfileView> upsertFormatProfile(
            @PathVariable("code") String code, @RequestBody BillFormatProfileRequest dto) {
        return ResponseEntity.ok(formatProfileService.upsert(code, dto));
    }

    /** Adding a country IS a create here — a row, not a deploy. */
    @PostMapping("/billFormatProfile")
    public ResponseEntity<BillFormatProfileView> createFormatProfile(
            @RequestBody BillFormatProfileRequest dto) {
        if (dto.code() == null || dto.code().isBlank()) {
            throw new com.bss.billing.exception.BadRequestException(
                    "a profile needs a code — the key the tenant's distribution format points at");
        }
        BillFormatProfileView created = formatProfileService.upsert(dto.code(), dto);
        return ResponseEntity.created(URI.create(ApiConstants.BASE_PATH
                + "/billFormatProfile/" + created.code())).body(created);
    }

    /** UNAPPLIED CASH: money the bank reported that no bill cleanly
     * claims — the AR worklist a human resolves. */
    @GetMapping("/remittance/unapplied")
    public ResponseEntity<List<UnappliedRemittanceView>> unappliedCash() {
        return ResponseEntity.ok(remittanceService.unappliedView(tenantScope.currentTenantId()));
    }

    /** Resolve ONE parked row to the bill it belongs to — back-office (or a
     * badged digital worker); the automatic path's guarantees apply. */
    @PostMapping("/remittance/unapplied/{id}/apply")
    public ResponseEntity<RemittanceApplied> applyUnapplied(@PathVariable("id") String id,
            @RequestBody ApplyUnappliedRequest body) {
        return ResponseEntity.ok(remittanceService.applyUnapplied(
                tenantScope.currentTenantId(), id, body.billId()));
    }

    /** THE DELIVERY LEDGER: what left for the distribution partner, when,
     * after how many tries — and what is still owed a retry. */
    @GetMapping("/billDistribution")
    public ResponseEntity<List<LedgerRow>> distributionLedger(
            @RequestParam(name = "status", required = false) String status) {
        return ResponseEntity.ok(distributionService.ledgerView(tenantScope.currentTenantId(), status));
    }

    /** An admin's second chance for a FAILED delivery. */
    @PostMapping("/billDistribution/{id}/retry")
    public ResponseEntity<RetryReceipt> retryDistribution(@PathVariable("id") String id) {
        return ResponseEntity.accepted()
                .body(distributionService.retry(tenantScope.currentTenantId(), id));
    }

    /** The disputes worklist (staff). */
    @GetMapping("/dispute")
    public ResponseEntity<List<DisputeView>> disputes() {
        return ResponseEntity.ok(disputeService.list());
    }

    /** The decision: credit an amount, or uphold with the reason. */
    @PostMapping("/dispute/{id}/resolve")
    public ResponseEntity<DisputeView> resolve(@PathVariable("id") String id,
            @RequestBody DisputeResolution dto) {
        return ResponseEntity.ok(disputeService.resolve(id, dto));
    }

    /** The DUNNING window: who is overdue, who broke, what is still owed —
     * installment stragglers plus every account's collection case. */
    @GetMapping("/dunning")
    public ResponseEntity<List<DunningRow>> dunning() {
        List<DunningRow> rows = new java.util.ArrayList<>(
                dunningService.dunningView(tenantScope.currentTenantId()));
        rows.addAll(collectionService.findCases(null));
        return ResponseEntity.ok(rows);
    }

    /** PAY IN PARTS: split an unpaid bill into 2-12 monthly installments. */
    @PostMapping("/customerBill/{id}/installmentPlan")
    public ResponseEntity<InstallmentPlanView> installmentPlan(@PathVariable("id") String id,
            @RequestBody(required = false) InstallmentPlanRequest dto) {
        return ResponseEntity.ok(service.createInstallmentPlan(id,
                dto == null ? InstallmentPlanRequest.EMPTY : dto));
    }

    /** One part lands: an authorized payment covering THIS installment. */
    @PostMapping("/customerBill/{id}/installmentPlan/pay")
    public ResponseEntity<InstallmentPlanView> payInstallment(@PathVariable("id") String id,
            @RequestBody InstallmentPaymentRequest dto) {
        return ResponseEntity.ok(service.payInstallment(id, dto));
    }
}
