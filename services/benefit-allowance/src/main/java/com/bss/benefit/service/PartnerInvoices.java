package com.bss.benefit.service;

import com.bss.benefit.dto.ChoiceRequests;
import com.bss.benefit.dto.PartnerCostView;
import com.bss.benefit.entity.PartnerInvoice;
import com.bss.benefit.events.DomainEventPublisher;
import com.bss.benefit.exception.NotFoundException;
import com.bss.benefit.repository.PartnerInvoiceRepository;
import com.bss.benefit.security.TenantRegistry;
import com.bss.benefit.security.TenantScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 */
@Service
public class PartnerInvoices {

    private final PartnerInvoiceRepository invoices;
    private final TenantScope tenantScope;
    private final TenantRegistry registry;
    private final DomainEventPublisher events;

    public PartnerInvoices(PartnerInvoiceRepository invoices, TenantScope tenantScope, TenantRegistry registry,
            DomainEventPublisher events) {
        this.invoices = invoices;
        this.tenantScope = tenantScope;
        this.registry = registry;
        this.events = events;
    }

    @Transactional
    public PartnerCostView.Invoice record(ChoiceRequests.PartnerInvoice request) {
        String tenant = tenantScope.currentTenantId();
        String partner = request.partner().trim();
        String reference = request.reference().trim();
        PartnerInvoice existing = invoices.findByTenantIdAndPartnerAndPeriodAndReference(
                tenant, partner, request.period(), reference).orElse(null);
        if (existing != null) {
            return view(existing);   // the same invoice again: one invoice, not two
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
        invoices.save(i);
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
            i.setPaidAt(OffsetDateTime.now());
            invoices.save(i);
            events.publish("PartnerInvoicePaidEvent", "partnerInvoice", eventOf(i));
        }
        return view(i);
    }

    static PartnerCostView.Invoice view(PartnerInvoice i) {
        return new PartnerCostView.Invoice(i.getId(), i.getReference(), i.getAmount(), i.getPaidAt() != null,
                i.getPaidAt() == null ? null : i.getPaidAt().toLocalDate().toString());
    }

    private static PartnerCostView.InvoiceEvent eventOf(PartnerInvoice i) {
        return new PartnerCostView.InvoiceEvent(i.getId(), i.getPartner(), i.getPeriod(), i.getReference(),
                i.getAmount(), i.getCurrency());
    }

    private String currency(String tenant) {
        TenantRegistry.TenantEntry t = registry.byId(tenant);
        return t == null || t.getCurrency() == null ? "EUR" : t.getCurrency();
    }
}
