package com.bss.benefit.service;

import com.bss.benefit.dto.ChoiceRequests;
import com.bss.benefit.dto.PartnerCostView;
import com.bss.benefit.entity.PartnerInvoice;
import com.bss.benefit.events.DomainEventPublisher;
import com.bss.benefit.exception.ConflictException;
import com.bss.benefit.exception.NotFoundException;
import com.bss.benefit.repository.PartnerCostRepository;
import com.bss.benefit.repository.PartnerInvoiceRepository;
import com.bss.benefit.security.TenantRegistry;
import com.bss.benefit.security.TenantScope;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Partner invoices (#390). The ledger records what the operator owes a partner
 * each month; the partner sends its own invoice. Finance records that invoice
 * here — partner, month, the partner's reference, the amount — and the month's
 * partner view sets it beside what was owed. Paying it is one act, once: it
 * publishes PartnerInvoicePaidEvent, which revenue books as the settlement of
 * the payable (debit the partner payable, credit cash). The same invoice
 * recorded twice is one invoice; an invoice paid twice is paid once.
 *
 * <p>Recorded again with a different amount, an unpaid invoice is CORRECTED (a
 * paid one is refused — the difference is its own invoice). Paying settles the
 * part of the month's accrual not already settled by the partner's other paid
 * invoices; anything invoiced above that rides the event as {@code accrued}
 * beside {@code amount}, so revenue books the variance instead of leaving the
 * payable short.
 */
@Service
public class PartnerInvoices {

    private final PartnerInvoiceRepository invoices;
    private final PartnerCostRepository costs;
    private final TenantScope tenantScope;
    private final TenantRegistry registry;
    private final DomainEventPublisher events;
    private final TransactionTemplate tx;

    public PartnerInvoices(PartnerInvoiceRepository invoices, PartnerCostRepository costs, TenantScope tenantScope,
            TenantRegistry registry, DomainEventPublisher events, TransactionTemplate tx) {
        this.invoices = invoices;
        this.costs = costs;
        this.tenantScope = tenantScope;
        this.registry = registry;
        this.events = events;
        this.tx = tx;
    }

    /**
     * Record (or correct) an invoice. Not one transaction around the insert: two
     * submits of the same invoice at once race on the unique key, and the loser
     * must read the winner's row rather than fail — so the insert runs alone and
     * a key violation is answered from the row that won.
     */
    public PartnerCostView.Invoice record(ChoiceRequests.PartnerInvoice request) {
        String tenant = tenantScope.currentTenantId();
        String partner = request.partner().trim();
        String reference = request.reference().trim();
        try {
            return tx.execute(s -> recordOrCorrect(tenant, partner, reference, request));
        } catch (DataIntegrityViolationException raced) {
            return tx.execute(s -> recordOrCorrect(tenant, partner, reference, request));
        }
    }

    private PartnerCostView.Invoice recordOrCorrect(String tenant, String partner, String reference,
            ChoiceRequests.PartnerInvoice request) {
        PartnerInvoice existing = invoices.findByTenantIdAndPartnerAndPeriodAndReference(
                tenant, partner, request.period(), reference).orElse(null);
        if (existing != null) {
            if (existing.getAmount().compareTo(request.amount()) == 0) {
                return view(existing);   // the same invoice again: one invoice, not two
            }
            if (existing.getPaidAt() != null) {
                throw new ConflictException("Invoice " + reference + " is already paid at " + existing.getAmount()
                        + " — record the difference as its own invoice");
            }
            existing.setAmount(request.amount());   // a corrected amount, before it is paid
            invoices.saveAndFlush(existing);
            events.publish("PartnerInvoiceRecordedEvent", "partnerInvoice", eventOf(existing));
            return view(existing);
        }
        PartnerInvoice i = new PartnerInvoice();
        i.setTenantId(tenant);
        i.setId(UUID.randomUUID().toString());
        i.setPartner(partner);
        i.setPeriod(request.period());
        i.setReference(reference);
        i.setAmount(request.amount());
        i.setCurrency(currency(tenant));
        i.setRecordedAt(OffsetDateTime.now());
        invoices.saveAndFlush(i);
        events.publish("PartnerInvoiceRecordedEvent", "partnerInvoice", eventOf(i));
        return view(i);
    }

    /** Mark an invoice paid — once. Paying a paid invoice changes nothing and books nothing. */
    @Transactional
    public PartnerCostView.Invoice pay(String id) {
        String tenant = tenantScope.currentTenantId();
        PartnerInvoice i = invoices.findByIdAndTenantId(id, tenant)
                .orElseThrow(() -> new NotFoundException("no partner invoice " + id));
        if (i.getPaidAt() == null) {
            i.setSettledAccrual(unsettledAccrual(tenant, i).min(i.getAmount()));
            i.setPaidAt(OffsetDateTime.now());
            invoices.save(i);
            events.publish("PartnerInvoicePaidEvent", "partnerInvoice", eventOf(i));
        }
        return view(i);
    }

    /** What the ledger accrued for this partner and month, less what its other paid invoices settled. */
    private BigDecimal unsettledAccrual(String tenant, PartnerInvoice invoice) {
        BigDecimal owed = costs.findByTenantIdAndPeriodOrderByPartnerAsc(tenant, invoice.getPeriod()).stream()
                .filter(c -> invoice.getPartner().equals(c.getPartner()))
                .map(com.bss.benefit.entity.PartnerCost::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal settled = invoices.findByTenantIdAndPeriodOrderByPartnerAscRecordedAtAsc(tenant, invoice.getPeriod())
                .stream().filter(o -> o.getPaidAt() != null && o.getSettledAccrual() != null
                        && invoice.getPartner().equals(o.getPartner()) && !o.getId().equals(invoice.getId()))
                .map(PartnerInvoice::getSettledAccrual).reduce(BigDecimal.ZERO, BigDecimal::add);
        return owed.subtract(settled).max(BigDecimal.ZERO);
    }

    static PartnerCostView.Invoice view(PartnerInvoice i) {
        return new PartnerCostView.Invoice(i.getId(), i.getReference(), i.getAmount(), i.getPaidAt() != null,
                i.getPaidAt() == null ? null : i.getPaidAt().toLocalDate().toString());
    }

    private static PartnerCostView.InvoiceEvent eventOf(PartnerInvoice i) {
        return new PartnerCostView.InvoiceEvent(i.getId(), i.getPartner(), i.getPeriod(), i.getReference(),
                i.getAmount(), i.getSettledAccrual(), i.getCurrency());
    }

    private String currency(String tenant) {
        TenantRegistry.TenantEntry t = registry.byId(tenant);
        return t == null || t.getCurrency() == null ? "EUR" : t.getCurrency();
    }
}
