package com.bss.communication.service;

import com.bss.communication.entity.CommunicationMessage;
import com.bss.communication.entity.Suppression;
import com.bss.communication.repository.CommunicationMessageRepository;
import com.bss.communication.repository.SuppressionRepository;
import com.bss.communication.security.TenantContext;
import com.bss.communication.security.TenantRegistry;
import com.bss.communication.security.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The ESP answers back: providers batch delivery events (SendGrid's Event
 * Webhook shape) to this receiver. Each event finds its message through
 * the echoed custom_args, stamps the delivery status, and a hard bounce /
 * complaint / unsubscribe puts the address on the tenant's SUPPRESSION
 * list — the forwarder skips it from then on. Compliance is not optional:
 * mailing a door that bounced is how operators land on blocklists.
 *
 * Auth: each event names its tenant (custom_args) and the whole batch
 * must carry that tenant's own ESP key — an event signed with the wrong
 * key is dropped, so one tenant's provider can never write another's
 * ledger.
 */
@Service
public class EspReceiptService {

    private static final Logger log = LoggerFactory.getLogger(EspReceiptService.class);
    /** Provider verdicts that mean STOP EMAILING this address. */
    private static final Set<String> SUPPRESSING = Set.of("bounce", "spamreport", "unsubscribe");

    private final CommunicationMessageRepository messages;
    private final SuppressionRepository suppressions;
    private final TenantRegistry tenants;
    private final TenantScope tenantScope;
    private final org.springframework.transaction.support.TransactionTemplate transactions;

    public EspReceiptService(CommunicationMessageRepository messages,
            SuppressionRepository suppressions, TenantRegistry tenants, TenantScope tenantScope,
            org.springframework.transaction.PlatformTransactionManager txManager) {
        this.messages = messages;
        this.suppressions = suppressions;
        this.tenants = tenants;
        this.tenantScope = tenantScope;
        this.transactions = new org.springframework.transaction.support.TransactionTemplate(txManager);
    }

    /** @return events accepted (bad tenant/token/args are dropped, not errors —
     *  webhooks retry; a poisoned batch must not wedge the provider's queue).
     *  Deliberately NOT @Transactional: the transaction must open AFTER the
     *  per-event actAs, or the connection's row-level tenant is the default's. */
    public int accept(List<Map<String, Object>> events, String token) {
        int accepted = 0;
        for (Map<String, Object> event : events == null ? List.<Map<String, Object>>of() : events) {
            try {
                if (applyOne(event, token)) {
                    accepted++;
                }
            } catch (Exception e) {
                log.warn("esp receipt skipped: {}", e.getMessage());
            }
        }
        return accepted;
    }

    private boolean applyOne(Map<String, Object> event, String token) {
        if (!(event.get("custom_args") instanceof Map<?, ?> args)
                || args.get("tenant") == null || args.get("messageId") == null) {
            return false;
        }
        String tenantId = String.valueOf(args.get("tenant"));
        TenantRegistry.TenantEntry tenant = tenants.byId(tenantId);
        if (tenant == null || tenant.getEspApiKey() == null
                || !tenant.getEspApiKey().equals(token)) {
            log.warn("esp receipt rejected: wrong key for tenant '{}'", tenantId);
            return false;
        }
        String verdict = String.valueOf(event.get("event"));
        String email = event.get("email") == null ? null : String.valueOf(event.get("email"));
        // the receiver runs outside any tenant request; act as the event's
        // (key-verified) tenant BEFORE the transaction opens, so the
        // connection's row-level tenant is this one
        try (TenantContext ignored = TenantContext.actAs(tenantId)) {
            transactions.executeWithoutResult(tx -> {
                CommunicationMessage message = messages
                        .findByIdAndTenantId(String.valueOf(args.get("messageId")), tenantId).orElse(null);
                if (message != null) {
                    message.setDeliveryStatus(verdict.length() > 32 ? verdict.substring(0, 32) : verdict);
                    message.setLastUpdate(OffsetDateTime.now());
                    messages.save(message);
                }
                if (SUPPRESSING.contains(verdict) && email != null
                        && !suppressions.existsByTenantIdAndEmail(tenantId, email)) {
                    Suppression s = new Suppression();
                    s.setId(UUID.randomUUID().toString());
                    s.setTenantId(tenantId);
                    s.setEmail(email);
                    s.setReason(verdict);
                    s.setCreatedAt(OffsetDateTime.now());
                    suppressions.save(s);
                    log.info("suppressed {} for tenant '{}' ({})", email, tenantId, verdict);
                }
            });
        }
        return true;
    }

    /** The tenant's do-not-email ledger, for staff eyes. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> suppressionList() {
        return suppressions.findByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId())
                .stream().map(s -> Map.<String, Object>of(
                        "email", s.getEmail(),
                        "reason", s.getReason(),
                        "since", s.getCreatedAt().toString()))
                .toList();
    }
}
