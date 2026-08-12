package com.bss.revenue.controller;

import com.bss.revenue.api.ApiConstants;
import com.bss.revenue.exception.BadRequestException;
import com.bss.revenue.service.RevenueService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * /revenue/v1 — the subledger's export face. No TMF Open API covers the
 * general ledger (deliberately outside the ODA map), so this is a house
 * API with the house disciplines: per-tenant, staff-only, evented.
 */
@RestController
@RequestMapping(ApiConstants.BASE_PATH)
public class RevenueController {

    private final RevenueService service;

    public RevenueController(RevenueService service) {
        this.service = service;
    }

    /**
     * Governed reporting summary for a period (defaults to month-to-date):
     * net revenue, tax, cash collected, invoices, and the prior-period delta —
     * computed once from the subledger. billing:read (GET /** is gated already).
     */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary(
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate) {
        LocalDate to = parseDate(toDate) != null ? parseDate(toDate) : LocalDate.now();
        LocalDate from = parseDate(fromDate) != null ? parseDate(fromDate) : to.withDayOfMonth(1);
        return ResponseEntity.ok(service.summary(from, to));
    }

    @GetMapping("/journalEntry")
    public ResponseEntity<List<Map<String, Object>>> journal(
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String sourceRef) {
        return ResponseEntity.ok(service.journal(parseDate(date), sourceRef));
    }

    @GetMapping("/journalEntry/{id}")
    public ResponseEntity<Map<String, Object>> entry(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.entryById(id));
    }

    @GetMapping(value = "/journalExport", produces = "text/csv")
    public ResponseEntity<String> export(@RequestParam(required = false) String date,
            @RequestParam(required = false) String format) {
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=journal-"
                        + (date == null ? "all" : date) + ".csv")
                .body(service.exportCsv(parseDate(date), format));
    }

    /** Obligation timelines for the ERP's rev-rec engine (ASC 606/IFRS 15). */
    @GetMapping("/revrecInput")
    public ResponseEntity<?> revrecInput(@RequestParam(required = false) String format) {
        if ("csv".equalsIgnoreCase(format)) {
            return ResponseEntity.ok().header("Content-Type", "text/csv")
                    .body(service.revrecCsv());
        }
        return ResponseEntity.ok(service.revrecInput());
    }

    @PostMapping("/loyaltyAccrual")
    public ResponseEntity<Map<String, Object>> loyaltyAccrual() {
        return ResponseEntity.ok(service.loyaltyAccrual());
    }

    @PostMapping("/periodClose")
    public ResponseEntity<Map<String, Object>> periodClose(@RequestBody Map<String, Object> dto) {
        if (dto.get("through") == null) {
            throw new BadRequestException("through (YYYY-MM-DD) is required");
        }
        return ResponseEntity.ok(service.closePeriod(String.valueOf(dto.get("through"))));
    }

    /** BNPL payout landed: clear the provider receivable (1100) to cash. Idempotent
     * by (provider, reference) — a payout file replayed twice books once. */
    @PostMapping("/remittance")
    public ResponseEntity<Map<String, Object>> remittance(@RequestBody Map<String, Object> dto) {
        try {
            return ResponseEntity.ok(service.postRemittance(dto));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }
    }

    @GetMapping(value = "/reconciliation", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> reconciliation(
            @RequestParam(required = false) String date) {
        return ResponseEntity.ok(service.reconciliation(parseDate(date)));
    }

    @GetMapping("/accountMapping")
    public ResponseEntity<List<Map<String, Object>>> chart() {
        return ResponseEntity.ok(service.chart());
    }

    @PostMapping("/accountMapping")
    public ResponseEntity<Map<String, Object>> remap(@RequestBody Map<String, Object> dto) {
        return ResponseEntity.ok(service.remap(dto));
    }

    @PostMapping("/backfill")
    public ResponseEntity<Map<String, Object>> backfill(@RequestBody Map<String, Object> dto) {
        if (dto.get("billId") == null) {
            throw new BadRequestException("billId is required");
        }
        return ResponseEntity.ok(service.backfill(String.valueOf(dto.get("billId"))));
    }

    private static LocalDate parseDate(String date) {
        if (date == null || date.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            throw new BadRequestException("date must be YYYY-MM-DD");
        }
    }
}
