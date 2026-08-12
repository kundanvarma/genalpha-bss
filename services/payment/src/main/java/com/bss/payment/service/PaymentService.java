package com.bss.payment.service;

import com.bss.payment.api.ApiConstants;
import com.bss.payment.api.OffsetPageRequest;
import com.bss.payment.api.PagedResult;
import com.bss.payment.dto.MoneyDto;
import com.bss.payment.dto.PaymentDto;
import com.bss.payment.entity.Payment;
import com.bss.payment.events.DomainEventPublisher;
import com.bss.payment.client.PaymentMethodClient;
import com.bss.payment.exception.BadRequestException;
import com.bss.payment.exception.ConflictException;
import com.bss.payment.exception.ScaRequiredException;
import com.bss.payment.exception.NotFoundException;
import com.bss.payment.psp.PspAdapter;
import com.bss.payment.repository.PaymentRepository;
import com.bss.payment.security.PartyScope;
import com.bss.payment.security.TenantScope;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import com.bss.payment.entity.PspConfig;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class PaymentService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PaymentService.class);

    private static final String RESOURCE = "Payment";

    /** The only legal moves: an authorization is either taken or given back. */
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            Payment.AUTHORIZED, Set.of(Payment.CAPTURED, Payment.VOIDED));

    private final PaymentRepository repository;
    private final com.bss.payment.psp.PspRouter pspRouter;
    private final com.bss.payment.psp.RedirectPspRegistry redirectRegistry;
    private final PspConfigService pspConfigs;
    private final PaymentMethodClient paymentMethods;
    private final DomainEventPublisher events;
    private final PartyScope partyScope;
    private final TenantScope tenantScope;

    public PaymentService(PaymentRepository repository, com.bss.payment.psp.PspRouter pspRouter,
            com.bss.payment.psp.RedirectPspRegistry redirectRegistry, PspConfigService pspConfigs,
            PaymentMethodClient paymentMethods, DomainEventPublisher events,
            PartyScope partyScope, TenantScope tenantScope) {
        this.repository = repository;
        this.pspRouter = pspRouter;
        this.redirectRegistry = redirectRegistry;
        this.pspConfigs = pspConfigs;
        this.paymentMethods = paymentMethods;
        this.events = events;
        this.partyScope = partyScope;
        this.tenantScope = tenantScope;
    }

    @Transactional(readOnly = true)
    public PagedResult<PaymentDto> findAll(int offset, int limit, Map<String, String> filters) {
        Payment probe = probeFor(filters);
        probe.setTenantId(tenantScope.currentTenantId());
        // Customers see their own payments only, whatever else they filter on.
        partyScope.scopedPartyId().ifPresent(probe::setOwnerPartyId);
        Page<Payment> page = repository.findAll(Example.of(probe), new OffsetPageRequest(offset, limit));
        return new PagedResult<>(page.getContent().stream().map(this::toDto).toList(), page.getTotalElements());
    }

    private Payment probeFor(Map<String, String> filters) {
        Payment probe = new Payment();
        for (Map.Entry<String, String> f : filters.entrySet()) {
            switch (f.getKey()) {
                case "id" -> probe.setId(f.getValue());
                case "status" -> probe.setStatus(f.getValue());
                case "correlatorId" -> probe.setCorrelatorId(f.getValue());
                default -> throw new BadRequestException("unsupported filter attribute '" + f.getKey() + "'");
            }
        }
        return probe;
    }

    @Transactional(readOnly = true)
    public PaymentDto findById(String id) {
        Payment entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        requireOwn(entity);
        return toDto(entity);
    }

    /**
     * Creating a payment IS the authorization: the PSP approves or the request
     * fails with a 409 and nothing is stored. Card details are used for the
     * PSP call only.
     */
    @Transactional
    public PaymentDto create(PaymentDto dto) {
        if (dto.getAmount() == null || dto.getAmount().getValue() == null
                || dto.getAmount().getValue().signum() <= 0) {
            throw new BadRequestException("amount must be positive");
        }
        String currency = dto.getAmount().getUnit() == null ? "EUR" : dto.getAmount().getUnit();
        Map<String, Object> method = dto.getPaymentMethod();
        // TMF670 seam: a saved method arrives as a reference, never as card
        // data. Resolve it in the vault (machine call), prove it belongs to
        // the payer, and pay with the vault token.
        if (method != null && method.get("id") != null && method.get("cardNumber") == null) {
            Map<String, Object> saved = paymentMethods.resolve(String.valueOf(method.get("id")));
            if (saved == null) {
                throw new BadRequestException("saved payment method not found");
            }
            Object methodOwner = ((java.util.List<Map<String, Object>>) saved.getOrDefault(
                    "relatedParty", java.util.List.of())).stream().map(p -> p.get("id")).findFirst().orElse(null);
            String payer = partyScope.scopedPartyId().orElse(null);
            if (payer != null && !payer.equals(methodOwner)) {
                throw new BadRequestException("saved payment method not found");
            }
            method = (Map<String, Object>) saved.get("details");
        }
        // Idempotency: a retried authorization (same correlator, same payer)
        // returns the original payment instead of holding funds twice — the
        // single most important thing a payment API must get right.
        if (dto.getCorrelatorId() != null) {
            Optional<Payment> prior = repository.findFirstByTenantIdAndCorrelatorId(
                    tenantScope.currentTenantId(), dto.getCorrelatorId());
            if (prior.isPresent()) {
                return toDto(prior.get());
            }
        }

        PspAdapter psp = pspRouter.forCurrentTenant();
        PspAdapter.Authorization auth = psp.authorize(
                dto.getAmount().getValue(), currency, method, dto.getCorrelatorId());
        if (auth.requiresAction()) {
            // Strong customer authentication (3-D Secure / BankID): the channel
            // completes the challenge and retries with the same correlator.
            throw new ScaRequiredException(auth.actionUrl());
        }
        if (!auth.approved()) {
            throw new ConflictException(auth.declineReason());
        }

        Payment entity = new Payment();
        entity.setTenantId(tenantScope.currentTenantId());
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setHref(ApiConstants.BASE_PATH + "/payment/" + id);
        entity.setDescription(dto.getDescription());
        entity.setStatus(Payment.AUTHORIZED);
        entity.setAmountValue(dto.getAmount().getValue());
        entity.setAmountUnit(currency);
        entity.setMethodType(dto.getPaymentMethod() == null ? null
                : String.valueOf(dto.getPaymentMethod().getOrDefault("@type", "bankCard")));
        entity.setMethodLabel(auth.methodLabel());
        entity.setAuthorizationCode(auth.authorizationCode());
        entity.setPspProvider(psp.provider());
        entity.setCorrelatorId(dto.getCorrelatorId());
        entity.setOwnerPartyId(partyScope.scopedPartyId().orElse(null));
        entity.setPaymentDate(OffsetDateTime.now());
        entity.setLastUpdate(OffsetDateTime.now());
        PaymentDto created = toDto(repository.save(entity));
        events.publish("PaymentCreateEvent", "payment", created);
        return created;
    }

    /* ---------- redirect / BNPL (PSP-P2) ---------- */

    /** The payment methods the current tenant offers (card + any redirect methods). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> methods() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String m : pspConfigs.methodsForCurrentTenant()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("method", m);
            row.put("redirect", !"card".equals(m));
            out.add(row);
        }
        return out;
    }

    /** Open a redirect session (Klarna/PayPal): returns where to send the customer.
     * Orchestration/failover — the method's provider is tried first; if it is
     * unreachable, any OTHER configured redirect provider takes over so a single
     * provider outage does not sink checkout. The response names the provider that
     * ACTUALLY served (and, on failover, the one it replaced), so the confirm leg
     * and the records stay truthful — a failover can switch the payment instrument
     * (e.g. Klarna→PayPal), which the caller sees via `failedOverFrom`. */
    @Transactional(readOnly = true)
    public Map<String, Object> createSession(Map<String, Object> dto) {
        String method = String.valueOf(dto.get("method"));
        String tenant = tenantScope.currentTenantId();
        PspConfig primary = pspConfigs.providerForMethod(tenant, method)
                .orElseThrow(() -> new BadRequestException("no provider configured for method '" + method + "'"));
        if (redirectRegistry.get(primary.getProvider()) == null) {
            throw new BadRequestException("no redirect adapter for '" + primary.getProvider() + "'");
        }
        BigDecimal amount = dto.get("amount") instanceof Map<?, ?> a && a.get("value") != null
                ? new BigDecimal(String.valueOf(a.get("value"))) : BigDecimal.ZERO;
        String currency = dto.get("amount") instanceof Map<?, ?> a2 && a2.get("unit") != null
                ? String.valueOf(a2.get("unit")) : "EUR";
        String returnUrl = String.valueOf(dto.getOrDefault("returnUrl", ""));

        // primary first, then every other configured redirect provider as backup
        List<PspConfig> candidates = new ArrayList<>();
        candidates.add(primary);
        for (PspConfig c : pspConfigs.enabledForTenant(tenant)) {
            if (!c.getProvider().equals(primary.getProvider())
                    && redirectRegistry.get(c.getProvider()) != null) {
                candidates.add(c);
            }
        }
        RuntimeException last = null;
        for (int i = 0; i < candidates.size(); i++) {
            PspConfig cfg = candidates.get(i);
            try {
                com.bss.payment.psp.RedirectPspAdapter.Session session = redirectRegistry.get(cfg.getProvider())
                        .createSession(cfg, amount, currency, returnUrl);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("sessionId", session.sessionId());
                out.put("redirectUrl", session.redirectUrl());
                out.put("provider", cfg.getProvider());
                if (i > 0) {
                    out.put("failedOverFrom", primary.getProvider());
                    log.warn("payment session for method '{}' failed over from {} to {}",
                            method, primary.getProvider(), cfg.getProvider());
                }
                out.put("@type", "PaymentSession");
                return out;
            } catch (RuntimeException e) {
                last = e;
                log.warn("createSession via {} failed ({}); {}", cfg.getProvider(), e.getMessage(),
                        i + 1 < candidates.size() ? "trying the next redirect provider" : "no backup left");
            }
        }
        throw new ConflictException("all redirect providers failed for method '" + method + "'"
                + (last == null ? "" : ": " + last.getMessage()));
    }

    /**
     * Confirm a redirect session (the return leg or the webhook) → the AUTHORIZED
     * payment. Idempotent by session id, so the return AND the webhook can't
     * double-book. Runs in the given tenant's scope.
     */
    @Transactional
    public PaymentDto confirmSession(String tenant, String provider, String sessionId) {
        var existing = repository.findFirstByTenantIdAndSessionRef(tenant, sessionId);
        if (existing.isPresent()) {
            return toDto(existing.get());
        }
        PspConfig cfg = pspConfigs.forTenantAndProvider(tenant, provider)
                .orElseThrow(() -> new BadRequestException("provider '" + provider + "' not configured"));
        com.bss.payment.psp.RedirectPspAdapter adapter = redirectRegistry.get(provider);
        if (adapter == null) {
            throw new BadRequestException("no redirect adapter for '" + provider + "'");
        }
        com.bss.payment.psp.RedirectPspAdapter.Confirmation c = adapter.confirm(cfg, sessionId);
        if (!c.approved()) {
            throw new ConflictException(c.declineReason() == null ? "session not approved" : c.declineReason());
        }
        Payment entity = new Payment();
        entity.setTenantId(tenant);
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setHref(ApiConstants.BASE_PATH + "/payment/" + id);
        entity.setStatus(Payment.AUTHORIZED);
        entity.setAmountValue(c.amount() == null ? BigDecimal.ZERO : c.amount());
        entity.setAmountUnit(c.currency() == null ? "EUR" : c.currency());
        entity.setMethodType(provider);
        entity.setMethodLabel(c.methodLabel());
        entity.setAuthorizationCode(c.authorizationCode());
        entity.setPspProvider(provider);
        entity.setSessionRef(sessionId);
        entity.setOwnerPartyId(partyScope.scopedPartyId().orElse(null));
        entity.setPaymentDate(OffsetDateTime.now());
        entity.setLastUpdate(OffsetDateTime.now());
        PaymentDto created = toDto(repository.save(entity));
        events.publish("PaymentCreateEvent", "payment", created);
        return created;
    }

    /** Confirm from the authenticated return leg (the current tenant + party). */
    @Transactional
    public PaymentDto confirm(String provider, String sessionId) {
        return confirmSession(tenantScope.currentTenantId(), provider, sessionId);
    }

    /** Capture via the redirect adapter (Klarna, by session — BNPL captures on ship)
     * or the card router (by authorization code). */
    private String captureVia(Payment entity, BigDecimal amount) {
        com.bss.payment.psp.RedirectPspAdapter redirect = redirectRegistry.get(entity.getPspProvider());
        if (redirect != null) {
            PspConfig cfg = pspConfigs.forTenantAndProvider(entity.getTenantId(), entity.getPspProvider())
                    .orElseThrow(() -> new ConflictException("provider '" + entity.getPspProvider() + "' not configured"));
            com.bss.payment.psp.RedirectPspAdapter.Settlement s =
                    redirect.capture(cfg, entity.getSessionRef(), amount, entity.getAmountUnit());
            if (!s.ok()) {
                throw new ConflictException("capture failed: " + s.failureReason());
            }
            return s.reference();
        }
        PspAdapter psp = pspRouter.byProvider(entity.getPspProvider());
        PspAdapter.Capture capture = psp.capture(entity.getAuthorizationCode(), amount, entity.getAmountUnit());
        if (!capture.settled()) {
            throw new ConflictException("capture failed: " + capture.failureReason());
        }
        return capture.captureRef();
    }

    private String refundVia(Payment entity, BigDecimal amount) {
        com.bss.payment.psp.RedirectPspAdapter redirect = redirectRegistry.get(entity.getPspProvider());
        if (redirect != null) {
            PspConfig cfg = pspConfigs.forTenantAndProvider(entity.getTenantId(), entity.getPspProvider())
                    .orElseThrow(() -> new ConflictException("provider '" + entity.getPspProvider() + "' not configured"));
            com.bss.payment.psp.RedirectPspAdapter.Settlement s =
                    redirect.refund(cfg, entity.getSessionRef(), amount, entity.getAmountUnit());
            if (!s.ok()) {
                throw new ConflictException("refund failed: " + s.failureReason());
            }
            return s.reference();
        }
        PspAdapter psp = pspRouter.byProvider(entity.getPspProvider());
        PspAdapter.Refund refund = psp.refund(entity.getAuthorizationCode(), amount, entity.getAmountUnit());
        if (!refund.refunded()) {
            throw new ConflictException("refund failed: " + refund.failureReason());
        }
        return refund.refundRef();
    }

    /**
     * A payment that ALREADY HAPPENED at the bank — remittance ingestion
     * (OCR/KID, camt.054) recording money that arrived by giro or credit
     * transfer. No PSP authorization: the bank's word is the event. It is
     * recorded AUTHORIZED so the bill-settle path applies its one guarantee
     * (validate owner + amount, capture atomically) exactly as for a card.
     * Idempotent on correlatorId — a re-sent bank file never books twice.
     */
    @Transactional
    public PaymentDto recordExternal(Map<String, Object> dto) {
        Object amount = dto.get("amount");
        if (!(amount instanceof Map<?, ?> amt) || amt.get("value") == null) {
            throw new BadRequestException("amount {unit, value} is required");
        }
        java.math.BigDecimal value = new java.math.BigDecimal(String.valueOf(amt.get("value")));
        if (value.signum() <= 0) {
            throw new BadRequestException("amount must be positive");
        }
        String correlator = dto.get("correlatorId") == null ? null : String.valueOf(dto.get("correlatorId"));
        if (correlator != null) {
            Optional<Payment> prior = repository.findFirstByTenantIdAndCorrelatorId(
                    tenantScope.currentTenantId(), correlator);
            if (prior.isPresent()) {
                return toDto(prior.get());
            }
        }
        Payment entity = new Payment();
        entity.setTenantId(tenantScope.currentTenantId());
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setHref(ApiConstants.BASE_PATH + "/payment/" + id);
        entity.setDescription(dto.get("description") == null ? "Bank transfer"
                : String.valueOf(dto.get("description")));
        entity.setStatus(Payment.AUTHORIZED);
        entity.setAmountValue(value);
        entity.setAmountUnit(amt.get("unit") == null ? "EUR" : String.valueOf(amt.get("unit")));
        entity.setMethodType("bankTransfer");
        entity.setMethodLabel("Bank transfer");
        entity.setAuthorizationCode(dto.get("reference") == null ? null
                : String.valueOf(dto.get("reference")));
        entity.setPspProvider("bank");
        entity.setCorrelatorId(correlator);
        entity.setOwnerPartyId(dto.get("ownerPartyId") == null ? null
                : String.valueOf(dto.get("ownerPartyId")));
        entity.setPaymentDate(OffsetDateTime.now());
        entity.setLastUpdate(OffsetDateTime.now());
        PaymentDto created = toDto(repository.save(entity));
        events.publish("PaymentCreateEvent", "payment", created);
        return created;
    }

    /** Status transitions (capture/void) and correlator linkage; nothing else changes after authorization. */
    @Transactional
    public PaymentDto patch(String id, PaymentDto patch) {
        Payment entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        requireOwn(entity);
        if (patch.getCorrelatorId() != null) {
            entity.setCorrelatorId(patch.getCorrelatorId());
        }
        if (patch.getStatus() != null && !patch.getStatus().equals(entity.getStatus())) {
            Set<String> allowed = TRANSITIONS.getOrDefault(entity.getStatus(), Set.of());
            if (!allowed.contains(patch.getStatus())) {
                throw new ConflictException("payment is '" + entity.getStatus()
                        + "' and cannot become '" + patch.getStatus() + "'");
            }
            // The status transition is where money actually moves: capture
            // settles the held authorization, void/refund reverses it. If the
            // PSP rejects the movement, the transition fails — the record never
            // claims money moved when it didn't.
            if (Payment.CAPTURED.equals(patch.getStatus())) {
                entity.setSettlementRef(captureVia(entity, entity.getAmountValue()));
            } else if (Payment.VOIDED.equals(patch.getStatus())) {
                entity.setSettlementRef(refundVia(entity, entity.getAmountValue()));
            }
            entity.setStatus(patch.getStatus());
        }
        entity.setLastUpdate(OffsetDateTime.now());
        PaymentDto updated = toDto(repository.save(entity));
        events.publish("PaymentStateChangeEvent", "payment", updated);
        return updated;
    }

    /**
     * REFUND, partial or full: the PSP must confirm the movement before the
     * record changes; refunds accumulate and can never exceed what was
     * captured. A fully refunded payment says so in its status.
     */
    @Transactional
    public Map<String, Object> refund(String id, Map<String, Object> dto) {
        Payment entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        requireOwn(entity);
        if (!Payment.CAPTURED.equals(entity.getStatus()) && !Payment.REFUNDED.equals(entity.getStatus())) {
            throw new ConflictException("only captured money can be refunded (status: "
                    + entity.getStatus() + ")");
        }
        java.math.BigDecimal amount = dto.get("amount") instanceof Map<?, ?> m && m.get("value") != null
                ? new java.math.BigDecimal(String.valueOf(m.get("value")))
                : entity.getAmountValue().subtract(entity.getRefundedAmount());
        java.math.BigDecimal refundable = entity.getAmountValue().subtract(entity.getRefundedAmount());
        if (amount.signum() <= 0 || amount.compareTo(refundable) > 0) {
            throw new ConflictException("refundable is " + refundable + " " + entity.getAmountUnit()
                    + "; asked for " + amount);
        }
        String refundRef = refundVia(entity, amount);
        entity.setRefundedAmount(entity.getRefundedAmount().add(amount));
        if (entity.getRefundedAmount().compareTo(entity.getAmountValue()) >= 0) {
            entity.setStatus(Payment.REFUNDED);
        }
        entity.setLastUpdate(OffsetDateTime.now());
        repository.save(entity);
        Map<String, Object> receipt = new java.util.LinkedHashMap<>();
        receipt.put("paymentId", entity.getId());
        receipt.put("amount", Map.of("value", amount, "unit", entity.getAmountUnit()));
        receipt.put("refundRef", refundRef);
        receipt.put("refundedTotal", entity.getRefundedAmount());
        receipt.put("status", entity.getStatus());
        receipt.put("reason", dto.get("reason") == null ? null : String.valueOf(dto.get("reason")));
        // a payment created by an unscoped caller (back-office, remittance)
        // has no owner party — the receipt simply omits the reference
        if (entity.getOwnerPartyId() != null) {
            receipt.put("relatedParty", java.util.List.of(
                    Map.of("id", entity.getOwnerPartyId(), "role", "customer")));
        }
        receipt.put("@type", "Refund");
        events.publish("PaymentRefundEvent", "refund", receipt);
        return receipt;
    }

    /**
     * Scoped tokens address only their own payments; anything else is a 404,
     * not a 403, so foreign ids do not leak existence.
     */
    private void requireOwn(Payment entity) {
        partyScope.scopedPartyId().ifPresent(own -> {
            if (!own.equals(entity.getOwnerPartyId())) {
                throw NotFoundException.forResource(RESOURCE, entity.getId());
            }
        });
    }

    private PaymentDto toDto(Payment entity) {
        PaymentDto dto = new PaymentDto();
        dto.setId(entity.getId());
        dto.setHref(entity.getHref());
        dto.setDescription(entity.getDescription());
        dto.setStatus(entity.getStatus());
        dto.setAmount(new MoneyDto(entity.getAmountUnit(), entity.getAmountValue()));
        if (entity.getMethodLabel() != null) {
            dto.setPaymentMethod(Map.of("@type", entity.getMethodType(), "label", entity.getMethodLabel()));
        }
        dto.setAuthorizationCode(entity.getAuthorizationCode());
        dto.setSettlementRef(entity.getSettlementRef());
        if (entity.getRefundedAmount() != null && entity.getRefundedAmount().signum() > 0) {
            dto.setRefundedAmount(entity.getRefundedAmount());
        }
        dto.setPspProvider(entity.getPspProvider());
        dto.setCorrelatorId(entity.getCorrelatorId());
        if (entity.getOwnerPartyId() != null) {
            dto.setRelatedParty(List.of(Map.of(
                    "id", entity.getOwnerPartyId(), "role", "payer", "@referredType", "Individual")));
        }
        dto.setPaymentDate(entity.getPaymentDate());
        dto.setLastUpdate(entity.getLastUpdate());
        dto.setType("Payment");
        return dto;
    }
}
