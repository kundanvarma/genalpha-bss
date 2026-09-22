package com.bss.catalog.service;

import com.bss.catalog.client.PolicyClient;
import com.bss.catalog.dto.EntityRef;
import com.bss.catalog.dto.EnvelopeRef;
import com.bss.catalog.dto.GovernanceRequest;
import com.bss.catalog.dto.GovernanceState;
import com.bss.catalog.dto.LaunchContext;
import com.bss.catalog.dto.LaunchDecision;
import com.bss.catalog.dto.LaunchDryRun;
import com.bss.catalog.dto.LedgerLine;
import com.bss.catalog.dto.Money;
import com.bss.catalog.dto.ProductOfferingDto;
import com.bss.catalog.dto.ReadinessItem;
import com.bss.catalog.entity.GovernanceLedger;
import com.bss.catalog.entity.ProductOffering;
import com.bss.catalog.entity.ProductOfferingPrice;
import com.bss.catalog.entity.ProductSpecification;
import com.bss.catalog.events.DomainEventPublisher;
import com.bss.catalog.exception.BadRequestException;
import com.bss.catalog.exception.NotFoundException;
import com.bss.catalog.mapper.ProductOfferingMapper;
import com.bss.catalog.repository.GovernanceLedgerRepository;
import com.bss.catalog.repository.ProductOfferingPriceRepository;
import com.bss.catalog.repository.ProductOfferingRepository;
import com.bss.catalog.repository.ProductSpecificationRepository;
import com.bss.catalog.security.TenantRegistry;
import com.bss.catalog.security.TenantScope;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Intent-to-launch governance — the decision between "someone wrote an offer"
 * and "customers can buy it". Three tenant modes: <b>none</b> (a write is a
 * launch, today's behaviour), <b>envelope</b> (an offer inside a pre-approved
 * envelope launches by itself and is ledgered as such; anything outside asks a
 * human) and <b>always</b> (every launch asks). The TMF620 resource is
 * untouched: governance lives beside it, exposed through the governance door,
 * and its every step is a ledger line and a ProductOfferingGovernanceEvent
 * (which the process service mirrors as a TMF701 flow).
 */
@Service
public class LaunchGovernanceService {

    private static final Logger log = LoggerFactory.getLogger(LaunchGovernanceService.class);
    private static final TypeReference<List<Map<String, Object>>> JSON_LIST = new TypeReference<>() { };
    private static final TypeReference<List<EntityRef>> REF_LIST = new TypeReference<>() { };
    private static final Pattern NUMBER = Pattern.compile("(\\d+(?:[.,]\\d+)?)");

    public static final String APPROVE_AUTHORITY = "catalog:approve";
    public static final String NONE = "none";
    public static final String REQUESTED = "requested";
    public static final String APPROVED = "approved";
    public static final String REJECTED = "rejected";
    public static final String HELD = "held";
    public static final String LAUNCHED = "launched";
    public static final String EXPIRED = "expired";
    public static final String EVENT = "ProductOfferingGovernanceEvent";

    private final ProductOfferingRepository offerings;
    private final ProductSpecificationRepository specs;
    private final ProductOfferingPriceRepository prices;
    private final GovernanceLedgerRepository ledger;
    private final TenantRegistry tenants;
    private final TenantScope scope;
    private final PolicyClient policy;
    private final DomainEventPublisher events;
    private final ObjectMapper json;
    private final ProductOfferingMapper mapper;

    public LaunchGovernanceService(ProductOfferingRepository offerings, ProductSpecificationRepository specs,
            ProductOfferingPriceRepository prices, GovernanceLedgerRepository ledger, TenantRegistry tenants,
            TenantScope scope, PolicyClient policy, DomainEventPublisher events, ObjectMapper json,
            ProductOfferingMapper mapper) {
        this.offerings = offerings;
        this.specs = specs;
        this.prices = prices;
        this.ledger = ledger;
        this.tenants = tenants;
        this.scope = scope;
        this.policy = policy;
        this.events = events;
        this.json = json;
        this.mapper = mapper;
    }

    /* ---------- tenant settings ---------- */

    public String mode() {
        TenantRegistry.TenantEntry e = tenants.byId(scope.currentTenantId());
        String m = e == null || e.getLaunchGovernance() == null ? NONE : e.getLaunchGovernance().trim().toLowerCase(Locale.ROOT);
        return List.of(NONE, "envelope", "always").contains(m) ? m : NONE;
    }

    public boolean on() {
        return !NONE.equals(mode());
    }

    public String aiProposals() {
        TenantRegistry.TenantEntry e = tenants.byId(scope.currentTenantId());
        return e == null || e.getAiProposals() == null ? "trust" : e.getAiProposals().trim().toLowerCase(Locale.ROOT);
    }

    private int expiryDays() {
        TenantRegistry.TenantEntry e = tenants.byId(scope.currentTenantId());
        return e == null || e.getApprovalExpiryDays() <= 0 ? 60 : e.getApprovalExpiryDays();
    }

    /** The readiness template: one tick per configured owner, "authority|label". */
    public List<ReadinessItem> readinessTemplate() {
        TenantRegistry.TenantEntry e = tenants.byId(scope.currentTenantId());
        List<ReadinessItem> out = new ArrayList<>();
        if (e == null || e.getLaunchReadiness() == null) {
            return out;
        }
        for (String line : e.getLaunchReadiness()) {
            if (line == null || !line.contains("|")) {
                continue;
            }
            String[] parts = line.split("\\|", 2);
            out.add(ReadinessItem.open(parts[0].trim(), parts[1].trim()));
        }
        return out;
    }

    /* ---------- who is asking ---------- */

    public boolean approver() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities() != null
                && auth.getAuthorities().stream().anyMatch(a -> APPROVE_AUTHORITY.equals(a.getAuthority()));
    }

    public boolean holds(String authority) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities() != null
                && auth.getAuthorities().stream().anyMatch(a -> authority.equals(a.getAuthority()));
    }

    public String actor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return "system";
        }
        if (auth.getPrincipal() instanceof Jwt jwt) {
            String u = jwt.getClaimAsString("preferred_username");
            if (u == null || u.isBlank()) {
                u = jwt.getClaimAsString("client_id");
            }
            if (u != null && !u.isBlank()) {
                return u;
            }
        }
        return auth.getName();
    }

    /* ---------- the gate the TMF door goes through ---------- */

    /** Create: with governance on, only an approver's write lands live; everyone else lands a draft. */
    public void beforeCreate(ProductOfferingDto dto) {
        if (!on()) {
            return;
        }
        if (LifecyclePolicy.launched(dto.getLifecycleStatus()) && !approver()) {
            dto.setLifecycleStatus("In design");
        }
    }

    public void afterCreate(ProductOffering entity) {
        if (!on()) {
            return;
        }
        if (LifecyclePolicy.launched(entity.getLifecycleStatus())) {
            GovernanceState g = state(entity);
            g.approvedAt = now();
            g.approvedBy = actor();
            g.substanceHash = substanceHash(entity);
            markLaunched(entity, g, "created live by " + actor() + " (approver)", null, null);
        }
    }

    /** Patch, before the fields land: a move to Launched must be approved (or the caller must be an approver). */
    public boolean beforePatch(ProductOffering entity, ProductOfferingDto patch) {
        if (!on()) {
            return false;
        }
        String target = patch.getLifecycleStatus();
        if (target != null && LifecyclePolicy.launched(target) && !LifecyclePolicy.launched(entity.getLifecycleStatus())) {
            requireLaunchable(entity, false);
            return true;
        }
        return false;
    }

    /** Patch, after the fields landed: a launch is ledgered; a substance change voids a pending approval. */
    public void afterPatch(ProductOffering entity, String hashBefore, boolean launchedNow) {
        if (!on()) {
            return;
        }
        GovernanceState g = state(entity);
        if (launchedNow) {
            markLaunched(entity, g, "launched by lifecycle change (" + actor() + ")", null, null);
            return;
        }
        String st = entity.getGovernanceState();
        if ((APPROVED.equals(st) || REQUESTED.equals(st)) && !Objects.equals(hashBefore, substanceHash(entity))) {
            entity.setGovernanceState(NONE);
            entity.setApprovalExpiresAt(null);
            g.voidedAt = now();
            g.voidedReason = "substance changed after " + st;
            store(entity, g);
            line(entity, "voided", "approval voided — price, allowance, category, terms or channels changed after it was "
                    + st + "; request again", null, null);
            emit(entity, g, "voided", "substance changed");
        }
    }

    /* ---------- the doors ---------- */

    @Transactional
    public LaunchDecision request(String id, GovernanceRequest body) {
        requireOn();
        ProductOffering e = load(id);
        String st = entity(e);
        if (REQUESTED.equals(st) || APPROVED.equals(st) || HELD.equals(st) || LAUNCHED.equals(st)) {
            return view(e);
        }
        GovernanceState g = new GovernanceState();
        g.requestedAt = now();
        g.requestedBy = actor();
        g.note = body.note();
        g.origin = body.origin() == null ? "human" : body.origin();
        g.readiness = readinessTemplate();
        g.substanceHash = substanceHash(e);
        e.setGovernanceState(REQUESTED);
        store(e, g);
        line(e, "requested", body.note(), null, null);
        emit(e, g, "requested", body.note());
        // an AI proposal in a "trust" tenant is judged like a human draft; in an
        // "approve" tenant it always asks, envelope or not
        boolean ai = "ai".equals(g.origin);
        if ("envelope".equals(mode()) && !(ai && "approve".equals(aiProposals()))) {
            PolicyClient.Decision verdict = envelopeVerdict(describe(e));
            if (verdict != null) {
                approveWith(e, g, "envelope", "pre-approved: inside envelope '" + verdict.ruleName() + "'"
                        + (verdict.message() == null ? "" : " — " + verdict.message()),
                        String.valueOf(verdict.ruleId()), String.valueOf(verdict.ruleName()));
            }
        }
        return view(e);
    }

    @Transactional
    public LaunchDecision approve(String id, GovernanceRequest body) {
        requireOn();
        requireApprover();
        ProductOffering e = load(id);
        String st = entity(e);
        if (LAUNCHED.equals(st) && LifecyclePolicy.launched(e.getLifecycleStatus())) {
            throw new BadRequestException("'" + e.getName() + "' is already launched");
        }
        if (HELD.equals(st)) {
            throw new BadRequestException("'" + e.getName() + "' is on hold — resume it first");
        }
        GovernanceState g = state(e);
        if (g.readiness == null) {
            g.readiness = readinessTemplate();
        }
        if (g.substanceHash == null) {
            g.substanceHash = substanceHash(e);
        }
        approveWith(e, g, actor(), body.note(), null, null);
        return view(e);
    }

    @Transactional
    public LaunchDecision reject(String id, GovernanceRequest body) {
        requireOn();
        requireApprover();
        ProductOffering e = load(id);
        GovernanceState g = state(e);
        e.setGovernanceState(REJECTED);
        e.setApprovalExpiresAt(null);
        g.rejectedAt = now();
        g.rejectedBy = actor();
        g.rejectedReason = body.note();
        store(e, g);
        line(e, "rejected", body.note(), null, null);
        emit(e, g, "rejected", body.note());
        return view(e);
    }

    /** Hold: any catalog writer may pull the brake (a hold is the safe direction). A dated hold
     *  pushes the window; an open-ended hold withdraws a live offer until someone resumes it. */
    @Transactional
    public LaunchDecision hold(String id, GovernanceRequest body) {
        requireOn();
        ProductOffering e = load(id);
        String st = entity(e);
        if (HELD.equals(st)) {
            return view(e);
        }
        if (REJECTED.equals(st) || NONE.equals(st) || EXPIRED.equals(st)) {
            throw new BadRequestException("only a requested, approved or launched offer can be held ('"
                    + e.getName() + "' is " + st + ")");
        }
        OffsetDateTime until = body.until() == null ? null : OffsetDateTime.parse(body.until());
        GovernanceState g = state(e);
        g.heldFrom = st;
        g.heldAt = now();
        g.heldBy = actor();
        g.holdReason = body.note();
        g.holdUntil = until == null ? null : until.toString();
        if (LifecyclePolicy.launched(e.getLifecycleStatus())) {
            if (until != null) {
                // "" = the offer had no launch date; a resume then puts it on sale now
                g.heldValidFrom = e.getValidFrom() == null ? "" : e.getValidFrom().toString();
                e.setValidFrom(until);
                e.setAnnouncedAt(null); // launch day fires again when the hold lifts
            } else {
                g.heldStatus = e.getLifecycleStatus();
                e.setLifecycleStatus("In test");
            }
        }
        e.setGovernanceState(HELD);
        e.setLaunchHoldUntil(until);
        store(e, g);
        line(e, "held", (until == null ? "held until resumed" : "held until " + until) + body.noteSuffix(), null, null);
        emit(e, g, "held", body.note());
        return view(e);
    }

    @Transactional
    public LaunchDecision resume(String id, GovernanceRequest body) {
        requireOn();
        ProductOffering e = load(id);
        if (!HELD.equals(entity(e))) {
            throw new BadRequestException("'" + e.getName() + "' is not on hold");
        }
        GovernanceState g = state(e);
        String back = g.heldFrom == null ? APPROVED : g.heldFrom;
        if (g.heldStatus != null) {
            e.setLifecycleStatus(g.heldStatus);
            g.heldStatus = null;
        }
        if (g.heldValidFrom != null) {
            String was = g.heldValidFrom;
            g.heldValidFrom = null;
            OffsetDateTime restored = was.isBlank() ? OffsetDateTime.now() : OffsetDateTime.parse(was);
            e.setValidFrom(restored.isAfter(OffsetDateTime.now()) ? restored : OffsetDateTime.now());
        }
        g.resumedAt = now();
        g.resumedBy = actor();
        g.holdUntil = null;
        e.setGovernanceState(back);
        e.setLaunchHoldUntil(null);
        store(e, g);
        line(e, "resumed", "back to " + back + body.noteSuffix(), null, null);
        emit(e, g, "resumed", body.note());
        return view(e);
    }

    /** A readiness tick: the owner (or an approver) says their part is done. */
    @Transactional
    public LaunchDecision ready(String id, GovernanceRequest body) {
        requireOn();
        ProductOffering e = load(id);
        String owner = body.owner();
        if (owner == null) {
            throw new BadRequestException("owner (the readiness authority, e.g. campaign:write) is required");
        }
        if (!approver() && !holds(owner)) {
            throw new BadRequestException("only the " + owner + " owner or an approver can tick this readiness");
        }
        GovernanceState g = state(e);
        List<ReadinessItem> readiness = new ArrayList<>(g.readinessOrEmpty());
        int at = -1;
        for (int i = 0; i < readiness.size(); i++) {
            if (owner.equals(readiness.get(i).owner())) {
                at = i;
                break;
            }
        }
        if (at < 0) {
            throw new BadRequestException("no readiness item is owned by " + owner);
        }
        ReadinessItem hit = readiness.get(at).ticked(!Boolean.FALSE.equals(body.done()), actor(), now(), body.note());
        readiness.set(at, hit);
        g.readiness = readiness;
        store(e, g);
        line(e, "ready", hit.label() + " — " + (hit.done() ? "done" : "reopened") + " by " + actor() + body.noteSuffix(), null, null);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("owner", owner);
        extra.put("done", hit.done());
        emit(e, g, "ready", body.note(), extra);
        return view(e);
    }

    /** Launch now: approved + not held + ready (an approver may force past readiness). */
    @Transactional
    public LaunchDecision launch(String id, GovernanceRequest body) {
        requireOn();
        ProductOffering e = load(id);
        boolean force = Boolean.TRUE.equals(body.force()) && approver();
        if (LAUNCHED.equals(entity(e)) && LifecyclePolicy.launched(e.getLifecycleStatus())) {
            return view(e);
        }
        requireLaunchable(e, force);
        GovernanceState g = state(e);
        if (body.channel() != null && !body.channel().isEmpty()) {
            List<EntityRef> list = new ArrayList<>();
            for (JsonNode o : body.channel()) {
                String cid = o.isObject() ? String.valueOf(o.get("id") == null || o.get("id").isNull() ? null : o.get("id").asText()) : o.asText();
                String name = Channels.REGISTERED.stream().filter(r -> r.get("id").equals(cid)).findFirst()
                        .map(r -> r.get("name")).orElse(null);
                list.add(EntityRef.of(cid, name));
            }
            list.forEach(ref -> Channels.requireKnownId(ref.id()));
            e.setChannelJson(writeRefs(list));
        }
        if (body.validFrom() != null) {
            e.setValidFrom(OffsetDateTime.parse(body.validFrom()));
        } else if (e.getValidFrom() == null || e.getValidFrom().isAfter(OffsetDateTime.now())) {
            e.setValidFrom(e.getValidFrom() == null ? OffsetDateTime.now() : e.getValidFrom());
        }
        if (body.validTo() != null) {
            e.setValidTo(OffsetDateTime.parse(body.validTo()));
        }
        e.setLifecycleStatus("Active");
        e.setAnnouncedAt(null);
        String skipped = force ? g.readinessOrEmpty().stream().filter(r -> !r.done())
                .map(ReadinessItem::label).reduce((a, b) -> a + ", " + b).orElse(null) : null;
        markLaunched(e, g, "launched by " + actor() + (skipped == null ? "" : " — forced past readiness: " + skipped)
                + body.noteSuffix(), null, null);
        return view(e);
    }

    /** Unlaunch: Retired with an end date — the trail keeps the launch, the shelf loses the offer. */
    @Transactional
    public LaunchDecision unlaunch(String id, GovernanceRequest body) {
        requireOn();
        ProductOffering e = load(id);
        if (!LifecyclePolicy.launched(e.getLifecycleStatus())) {
            throw new BadRequestException("'" + e.getName() + "' is not launched");
        }
        OffsetDateTime end = body.endDate() == null ? OffsetDateTime.now() : OffsetDateTime.parse(body.endDate());
        GovernanceState g = state(e);
        e.setValidTo(end);
        if (!end.isAfter(OffsetDateTime.now())) {
            e.setLifecycleStatus("Retired");
        }
        e.setGovernanceState(NONE);
        e.setApprovalExpiresAt(null);
        g.unlaunchedAt = now();
        g.unlaunchedBy = actor();
        g.unlaunchEnd = end.toString();
        store(e, g);
        line(e, "unlaunched", "sales end " + end + body.noteSuffix(), null, null);
        emit(e, g, "unlaunched", body.note());
        return view(e);
    }

    /** Envelope dry-run for a draft that may not be saved yet: would it launch by itself? */
    public LaunchDryRun dryRun(ProductOfferingDto dto) {
        LaunchContext ctx = describe(dto);
        PolicyClient.Decision verdict = "envelope".equals(mode()) ? envelopeVerdict(ctx) : null;
        EnvelopeRef envelope = verdict == null ? null : new EnvelopeRef(String.valueOf(verdict.ruleId()),
                String.valueOf(verdict.ruleName()), verdict.message() == null ? "" : verdict.message());
        String text = verdict != null ? "launches by itself — inside envelope '" + verdict.ruleName() + "'"
                : NONE.equals(mode()) ? "launch governance is off for this tenant — a write is a launch"
                : "always".equals(mode()) ? "needs an approver — this tenant approves every launch"
                : "needs an approver — outside every envelope";
        return new LaunchDryRun(mode(), ctx, verdict != null, envelope, text);
    }

    @Transactional(readOnly = true)
    public LaunchDecision view(String id) {
        return view(load(id));
    }

    /** The approvals desk: everything waiting on a decision, a tick or a resume. */
    @Transactional(readOnly = true)
    public List<LaunchDecision> queue() {
        List<LaunchDecision> out = new ArrayList<>();
        for (ProductOffering e : offerings.findByTenantId(scope.currentTenantId())) {
            String st = e.getGovernanceState();
            if (st == null || NONE.equals(st)) {
                continue;
            }
            if (LAUNCHED.equals(st) && e.getValidFrom() != null && e.getValidFrom().isBefore(OffsetDateTime.now().minusDays(7))) {
                continue; // launched a while ago: the shelf, not the desk
            }
            out.add(view(e));
        }
        out.sort((a, b) -> b.lastUpdate().compareTo(a.lastUpdate()));
        return out;
    }

    /* ---------- the clock (called from the launch tick, already acting as the tenant) ---------- */

    public void tick(ProductOffering e, OffsetDateTime now) {
        String st = e.getGovernanceState();
        if (APPROVED.equals(st) && e.getApprovalExpiresAt() != null && e.getApprovalExpiresAt().isBefore(now)) {
            GovernanceState g = state(e);
            e.setGovernanceState(EXPIRED);
            g.expiredAt = now.toString();
            store(e, g);
            offerings.save(e);
            line(e, "expired", "approval expired on " + e.getApprovalExpiresAt() + " — request again", null, null);
            emit(e, g, "expired", null);
        } else if (HELD.equals(st) && e.getLaunchHoldUntil() != null && e.getLaunchHoldUntil().isBefore(now)
                && LifecyclePolicy.launched(e.getLifecycleStatus())) {
            GovernanceState g = state(e);
            g.resumedAt = now.toString();
            g.resumedBy = "clock";
            g.heldValidFrom = null;
            e.setGovernanceState(LAUNCHED);
            e.setLaunchHoldUntil(null);
            store(e, g);
            offerings.save(e);
            line(e, "resumed", "hold elapsed — back on sale", null, null);
            emit(e, g, "resumed", "hold elapsed");
        }
    }

    /* ---------- the envelope context ---------- */

    public LaunchContext describe(ProductOffering e) {
        return describe(mapper.toDto(e));
    }

    /** What an envelope may look at: plain facts, in plain units. */
    public LaunchContext describe(ProductOfferingDto dto) {
        List<String> categories = new ArrayList<>();
        List<String> categoryIds = new ArrayList<>();
        for (Map<String, Object> c : nullSafe(dto.getCategory())) {
            if (c.get("name") != null) {
                categories.add(String.valueOf(c.get("name")).toLowerCase(Locale.ROOT));
            }
            if (c.get("id") != null) {
                categoryIds.add(String.valueOf(c.get("id")));
            }
        }
        // price: the lowest recurring charge if there is one, else the lowest one-time
        Double recurring = null;
        Double oneTime = null;
        String currency = null;
        for (Map<String, Object> ref : nullSafe(dto.getProductOfferingPrice())) {
            if (ref.get("id") == null) {
                continue;
            }
            ProductOfferingPrice p = prices.findByIdAndTenantId(String.valueOf(ref.get("id")), scope.currentTenantId()).orElse(null);
            if (p == null) {
                continue;
            }
            Money price = readMoney(p.getPriceJson());
            Double value = price == null || price.value() == null ? null : price.value().doubleValue();
            if (value == null) {
                continue;
            }
            if (currency == null && price.unit() != null) {
                currency = price.unit();
            }
            if ("recurring".equalsIgnoreCase(p.getPriceType())) {
                recurring = recurring == null ? value : Math.min(recurring, value);
            } else {
                oneTime = oneTime == null ? value : Math.min(oneTime, value);
            }
        }
        Double price = recurring != null ? recurring : oneTime != null ? oneTime : 0d;
        String priceType = recurring != null ? "recurring" : oneTime != null ? "oneTime" : "none";
        // spec characteristics: allowance and validity, parsed to numbers
        String specification = null;
        Map<String, Object> chars = new LinkedHashMap<>();
        if (dto.getProductSpecification() != null && dto.getProductSpecification().id() != null) {
            ProductSpecification spec = specs.findByIdAndTenantId(dto.getProductSpecification().id(), scope.currentTenantId()).orElse(null);
            if (spec != null) {
                specification = spec.getName();
                for (Map<String, Object> ch : readList(spec.getProductSpecCharacteristicJson())) {
                    Object name = ch.get("name");
                    if (name == null) {
                        continue;
                    }
                    Object first = null;
                    if (ch.get("productSpecCharacteristicValue") instanceof List<?> vals && !vals.isEmpty()
                            && vals.get(0) instanceof Map<?, ?> v) {
                        first = v.get("value");
                    }
                    chars.put(String.valueOf(name), first);
                }
            }
        }
        List<String> channels = new ArrayList<>();
        for (Map<String, Object> c : nullSafe(dto.getChannel())) {
            if (c.get("id") != null) {
                channels.add(String.valueOf(c.get("id")));
            }
        }
        if (channels.isEmpty()) {
            Channels.REGISTERED.forEach(r -> channels.add(r.get("id")));
        }
        List<String> terms = new ArrayList<>();
        for (Map<String, Object> t : nullSafe(dto.getProductOfferingTerm())) {
            if (t.get("name") != null) {
                terms.add(String.valueOf(t.get("name")).toLowerCase(Locale.ROOT));
            }
        }
        return new LaunchContext(dto.getName(), categories, categoryIds, price, priceType, currency, specification, chars,
                gigabytes(firstOf(chars, "Data", "data", "allowance", "Allowance", "dataAllowance")),
                days(firstOf(chars, "Validity", "validity", "validityDays")),
                firstOf(chars, "zeroRatedApps", "Zero-rated apps") != null,
                channels, channels.size(), terms, Boolean.TRUE.equals(dto.getIsBundle()));
    }

    private PolicyClient.Decision envelopeVerdict(LaunchContext ctx) {
        PolicyClient.Decision v = policy.decision("launch", ctx);
        if (v == null || !"allow".equals(v.decision()) || v.ruleId() == null) {
            return null;
        }
        return v;
    }

    /* ---------- internals ---------- */

    private void requireOn() {
        if (!on()) {
            throw new BadRequestException("launch governance is off for this tenant (launch-governance: none) — a write is a launch");
        }
    }

    private void requireApprover() {
        if (!approver()) {
            throw new BadRequestException("only a launch approver (" + APPROVE_AUTHORITY + ") can decide this");
        }
    }

    private void requireLaunchable(ProductOffering e, boolean force) {
        String st = entity(e);
        GovernanceState g = state(e);
        if (HELD.equals(st)) {
            throw new BadRequestException("'" + e.getName() + "' is on hold"
                    + (e.getLaunchHoldUntil() == null ? "" : " until " + e.getLaunchHoldUntil()) + " — resume it first");
        }
        if (APPROVED.equals(st)) {
            if (e.getApprovalExpiresAt() != null && e.getApprovalExpiresAt().isBefore(OffsetDateTime.now())) {
                tick(e, OffsetDateTime.now());
                throw new BadRequestException("the approval of '" + e.getName() + "' expired on "
                        + e.getApprovalExpiresAt() + " — request it again");
            }
            List<String> open = g.readinessOrEmpty().stream().filter(r -> !r.done()).map(ReadinessItem::label).toList();
            if (!open.isEmpty() && !force) {
                throw new BadRequestException("'" + e.getName() + "' is approved but not ready: " + String.join(", ", open)
                        + (approver() ? " — an approver may launch anyway with force" : ""));
            }
            return;
        }
        if (approver()) {
            // the approver's one-stroke path: approve and launch in the same breath
            if (g.readiness == null) {
                g.readiness = readinessTemplate();
            }
            g.substanceHash = substanceHash(e);
            approveWith(e, g, actor(), "approved directly by " + actor(), null, null);
            return;
        }
        throw new BadRequestException("'" + e.getName() + "' needs launch approval (state: " + st + ", tenant mode: "
                + mode() + ") — request it: POST productOffering/" + e.getId() + "/governance/request");
    }

    private void approveWith(ProductOffering e, GovernanceState g, String by, String note, String envelopeId, String envelopeName) {
        e.setGovernanceState(APPROVED);
        e.setApprovalExpiresAt(OffsetDateTime.now().plusDays(expiryDays()));
        g.approvedAt = now();
        g.approvedBy = by;
        g.approvalNote = note;
        g.envelope = envelopeId != null ? new EnvelopeRef(envelopeId, envelopeName) : null;
        store(e, g);
        line(e, "approved", note, envelopeId, envelopeName);
        emit(e, g, "approved", note);
    }

    private void markLaunched(ProductOffering e, GovernanceState g, String note, String envelopeId, String envelopeName) {
        e.setGovernanceState(LAUNCHED);
        e.setLaunchHoldUntil(null);
        g.launchedAt = now();
        g.launchedBy = actor();
        g.launchedChannels = readRefs(e.getChannelJson()).stream().map(c -> String.valueOf(c.id())).toList();
        store(e, g);
        line(e, "launched", note, envelopeId, envelopeName);
        emit(e, g, "launched", note);
    }

    private ProductOffering load(String id) {
        return offerings.findByIdAndTenantId(id, scope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("ProductOffering", id));
    }

    private String entity(ProductOffering e) {
        return e.getGovernanceState() == null ? NONE : e.getGovernanceState();
    }

    /** The stored governance state of an offering; a fresh one when nothing is stored yet. */
    public GovernanceState state(ProductOffering e) {
        String s = e.getGovernanceJson();
        if (s == null || s.isBlank()) {
            return new GovernanceState();
        }
        try {
            return json.readValue(s, GovernanceState.class);
        } catch (Exception ex) {
            return new GovernanceState();
        }
    }

    private void store(ProductOffering e, GovernanceState g) {
        try {
            e.setGovernanceJson(json.writeValueAsString(g));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        e.setLastUpdate(OffsetDateTime.now());
    }

    private void line(ProductOffering e, String action, String note, String envelopeId, String envelopeName) {
        GovernanceLedger l = new GovernanceLedger();
        l.setId(UUID.randomUUID().toString());
        l.setTenantId(e.getTenantId());
        l.setOfferingId(e.getId());
        l.setAction(action);
        l.setActor(actor());
        l.setNote(note == null ? null : note.substring(0, Math.min(1000, note.length())));
        l.setEnvelopeId(envelopeId);
        l.setEnvelopeName(envelopeName);
        l.setAt(OffsetDateTime.now());
        ledger.save(l);
    }

    private void emit(ProductOffering e, GovernanceState g, String action, String note) {
        emit(e, g, action, note, Map.of());
    }

    private void emit(ProductOffering e, GovernanceState g, String action, String note, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", e.getId());
        body.put("name", e.getName());
        body.put("action", action);
        body.put("state", e.getGovernanceState());
        body.put("lifecycleStatus", e.getLifecycleStatus());
        body.put("actor", actor());
        body.put("note", note);
        body.put("readiness", g.readiness);
        body.put("envelope", g.envelope);
        body.put("holdUntil", e.getLaunchHoldUntil() == null ? null : e.getLaunchHoldUntil().toString());
        body.put("approvalExpiresAt", e.getApprovalExpiresAt() == null ? null : e.getApprovalExpiresAt().toString());
        body.put("validFrom", e.getValidFrom() == null ? null : e.getValidFrom().toString());
        body.putAll(extra);
        events.publish(EVENT, "productOffering", body);
        log.info("governance {}: '{}' -> {} ({})", action, e.getName(), e.getGovernanceState(), note);
    }

    private LaunchDecision view(ProductOffering e) {
        GovernanceState st = state(e);
        // the stored state rides unwrapped in the view; its holdUntil and readiness have their own place there
        GovernanceState unwrapped = json.convertValue(st, GovernanceState.class);
        unwrapped.holdUntil = null;
        unwrapped.readiness = null;
        String holdUntil = st.holdUntil != null ? st.holdUntil
                : e.getLaunchHoldUntil() == null ? null : e.getLaunchHoldUntil().toString();
        List<LedgerLine> trail = new ArrayList<>();
        for (GovernanceLedger l : ledger.findAllByTenantIdAndOfferingIdOrderByAtAsc(e.getTenantId(), e.getId())) {
            trail.add(new LedgerLine(l.getAction(), l.getActor(), l.getNote(), l.getEnvelopeId(), l.getEnvelopeName(),
                    l.getAt().toString()));
        }
        return new LaunchDecision(e.getId(), e.getName(), e.getLifecycleStatus(),
                e.getValidFrom() == null ? null : e.getValidFrom().toString(),
                e.getValidTo() == null ? null : e.getValidTo().toString(),
                entity(e), mode(), holdUntil,
                e.getApprovalExpiresAt() == null ? null : e.getApprovalExpiresAt().toString(),
                readRefs(e.getChannelJson()),
                e.getLastUpdate() == null ? "" : e.getLastUpdate().toString(),
                unwrapped, st.readinessOrEmpty(), trail, approver());
    }

    /** The substance an approval covers: what a customer pays, gets and where. Dates and copy are free to edit. */
    public String substanceHash(ProductOffering e) {
        String raw = String.join("|", String.valueOf(e.getName()), String.valueOf(e.getProductOfferingPriceJson()),
                String.valueOf(e.getProductSpecificationJson()), String.valueOf(e.getCategoryJson()),
                String.valueOf(e.getProductOfferingTermJson()), String.valueOf(e.getChannelJson()),
                String.valueOf(e.getBundledProductOfferingJson()), String.valueOf(e.getIsBundle()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        } catch (Exception ex) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    private Money readMoney(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return json.readValue(s, Money.class);
        } catch (Exception ex) {
            return null;
        }
    }

    private List<Map<String, Object>> readList(String s) {
        if (s == null || s.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return json.readValue(s, JSON_LIST);
        } catch (Exception ex) {
            return new ArrayList<>();
        }
    }

    private List<EntityRef> readRefs(String s) {
        if (s == null || s.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return json.readValue(s, REF_LIST);
        } catch (Exception ex) {
            return new ArrayList<>();
        }
    }

    private String writeRefs(List<EntityRef> refs) {
        try {
            return json.writeValueAsString(refs);
        } catch (Exception ex) {
            throw new IllegalArgumentException("unserializable channel list", ex);
        }
    }

    private static List<Map<String, Object>> nullSafe(List<Map<String, Object>> l) {
        return l == null ? List.of() : l;
    }

    private static Object firstOf(Map<String, Object> chars, String... names) {
        for (String n : names) {
            if (chars.get(n) != null) {
                return chars.get(n);
            }
        }
        return null;
    }

    /** "100 GB" → 100, "1.5 TB" → 1536, "500 MB" → 0.49, "Unlimited" → 9999, else null. */
    static Double gigabytes(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        if (s.contains("unlimited") || s.contains("ubegrenset")) {
            return 9999d;
        }
        Matcher m = NUMBER.matcher(s);
        if (!m.find()) {
            return null;
        }
        double n = Double.parseDouble(m.group(1).replace(',', '.'));
        if (s.contains("tb")) {
            return n * 1024;
        }
        if (s.contains("mb")) {
            return Math.round(n / 1024 * 100) / 100d;
        }
        return n;
    }

    /** "30 days" → 30, "6 hours" → 0.25, "1 month" → 30, "12 months" → 360, "1 year" → 365, else null. */
    static Double days(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        Matcher m = NUMBER.matcher(s);
        if (!m.find()) {
            return null;
        }
        double n = Double.parseDouble(m.group(1).replace(',', '.'));
        if (s.contains("hour") || s.contains("time")) {
            return Math.round(n / 24 * 100) / 100d;
        }
        if (s.contains("week") || s.contains("uke")) {
            return n * 7;
        }
        if (s.contains("month") || s.contains("mnd") || s.contains("måned")) {
            return n * 30;
        }
        if (s.contains("year") || s.contains("år")) {
            return n * 365;
        }
        return n;
    }

    private static String now() {
        return OffsetDateTime.now().toString();
    }
}
