package com.bss.billing.service;

import com.bss.billing.entity.DunningPolicy;
import com.bss.billing.repository.DunningPolicyRepository;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

/**
 * How long a customer has to pay. The term already existed — the dunning
 * policy has carried {@code paymentTermDays} since the collections ladder was
 * built, and the collections sweep added it to the bill date on every pass to
 * decide who was late. Reading it in one place means the bill can be stamped
 * with its due date once, at bill run, so every screen and every sweep agree
 * on the same day.
 *
 * <p>There is no per-account term in this data model: the term is the
 * tenant's active dunning policy, and {@link #DEFAULT_DAYS} when a tenant has
 * no policy yet (the same default the policy entity itself carries, so a
 * tenant that later writes a policy changes nothing for bills already sent).
 */
@Service
public class PaymentTerms {

    /** The house default, matching {@code DunningPolicy.paymentTermDays}. */
    public static final int DEFAULT_DAYS = 14;

    private final DunningPolicyRepository policies;

    public PaymentTerms(DunningPolicyRepository policies) {
        this.policies = policies;
    }

    /** The term this tenant sells on, in days. */
    public int daysFor(String tenantId) {
        return policies.findFirstByTenantIdAndActiveTrueOrderByCreatedAtAsc(tenantId)
                .map(DunningPolicy::getPaymentTermDays)
                .filter(days -> days > 0)
                .orElse(DEFAULT_DAYS);
    }

    /** The day a bill raised now falls due, and the term that decided it. */
    public Term at(String tenantId, OffsetDateTime billDate) {
        int days = daysFor(tenantId);
        return new Term(days, billDate == null ? null : billDate.plusDays(days));
    }

    /** @param days the payment term applied · @param dueDate the day it falls due */
    public record Term(int days, OffsetDateTime dueDate) {
    }
}
