package com.bss.billing.service;

import com.bss.billing.entity.CustomerBill;
import com.bss.billing.repository.CustomerBillRepository;
import com.bss.billing.security.TenantScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * THE CHAOS TWIN: price a failure mode in currency, read-only, off the real
 * ledger. Scenario psp-outage: for N days nothing collects — the report is
 * the AR truly at risk (open bills), who is hit, and how much of it ages
 * beyond payment terms before the outage ends. Assumptions on its face.
 */
@Service
public class ChaosReportService {

    private static final int PAYMENT_TERMS_DAYS = 30;

    private final CustomerBillRepository bills;
    private final TenantScope tenantScope;
    private final TenantClock clock;

    public ChaosReportService(CustomerBillRepository bills, TenantScope tenantScope, TenantClock clock) {
        this.bills = bills;
        this.tenantScope = tenantScope;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> pspOutage(int days) {
        String tenant = tenantScope.currentTenantId();
        LocalDate horizon = clock.today().plusDays(days);
        BigDecimal atRisk = BigDecimal.ZERO;
        int open = 0;
        int agedBeyondTerms = 0;
        String currency = null;
        for (CustomerBill b : bills.findByTenantId(tenant)) {
            if (b.getAmountDueValue() == null || b.getAmountDueValue().signum() <= 0
                    || "settled".equalsIgnoreCase(b.getState()) || "paid".equalsIgnoreCase(b.getState())) {
                continue;
            }
            open++;
            atRisk = atRisk.add(b.getAmountDueValue());
            currency = currency != null ? currency : b.getAmountDueUnit();
            if (b.getBillDate() != null
                    && b.getBillDate().toLocalDate().plusDays(PAYMENT_TERMS_DAYS).isBefore(horizon)) {
                agedBeyondTerms++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("@type", "ChaosReport");
        out.put("scenario", "psp-outage");
        out.put("outageDays", days);
        out.put("openBills", open);
        out.put("revenueAtRisk", atRisk);
        if (currency != null) {
            out.put("currency", currency);
        }
        out.put("billsAgedBeyondTermsAtHorizon", agedBeyondTerms);
        out.put("assumptions", List.of(
                "for " + days + " days no payment collects — every open bill is exposure",
                "payment terms assumed " + PAYMENT_TERMS_DAYS + " days from bill date",
                "the horizon reads the TENANT clock — in a sandbox clone this composes with time compression",
                "read-only: nothing was changed, billed or messaged"));
        return out;
    }
}
