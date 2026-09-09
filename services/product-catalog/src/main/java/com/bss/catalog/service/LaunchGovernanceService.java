package com.bss.catalog.service;

import com.bss.catalog.client.PolicyClient;
import com.bss.catalog.dto.ProductOfferingDto;
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
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() { };
    private static final TypeReference<List<Map<String, Object>>> JSON_LIST = new TypeReference<>() { };
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
    public List<Map<String, Object>> readinessTemplate() {
        TenantRegistry.TenantEntry e = tenants.byId(scope.currentTenantId());
        List<Map<String, Object>> out = new ArrayList<>();
        if (e == null || e.getLaunchReadiness() == null) {
            return out;
        }
        for (String line : e.getLaunchReadiness()) {
            if (line == null || !line.contains("|")) {
                continue;
            }
            String[] parts = line.split("\\|", 2);
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("owner", parts[0].trim());
            t.put("label", parts[1].trim());
            t.put("done", false);
            out.add(t);
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
            Map<String, Object> g = state(entity);
            g.put("approvedAt", now());
            g.put("approvedBy", actor());
            g.put("substanceHash", substanceHash(entity));
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
        Map<String, Object> g = state(entity);
        if (launchedNow) {
            markLaunched(entity, g, "launched by lifecycle change (" + actor() + ")", null, null);
            return;
        }
        String st = entity.getGovernanceState();
        if ((APPROVED.equals(st) || REQUESTED.equals(st)) && !Objects.equals(hashBefore, substanceHash(entity))) {
            entity.setGovernanceState(NONE);
            entity.setApprovalExpiresAt(null);
            g.put("voidedAt", now());
            g.put("voidedReason", "substance changed after " + st);
            store(entity, g);
            line(entity, "voided", "approval voided — price, allowance, category, terms or channels changed after it was "
                    + st + "; request again", null, null);
            emit(entity, g, "voided", "substance changed");
        }
    }

    /* ---------- the doors ---------- */

    @Transactional
    public Map<String, Object> request(String id, Map<String, Object> body) {
        requireOn();
        ProductOffering e = load(id);
        String st = entity(e);
        if (REQUESTED.equals(st) || APPROVED.equals(st) || HELD.equals(st) || LAUNCHED.equals(st)) {
            return view(e);
        }
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("requestedAt", now());
        g.put("requestedBy", actor());
        g.put("note", str(body.get("note")));
        g.put("origin", body.get("origin") == null ? "human" : String.valueOf(body.get("origin")));
        g.put("readiness", readinessTemplate());
        g.put("substanceHash", substanceHash(e));
        e.setGovernanceState(REQUESTED);
        store(e, g);
        line(e, "requested", str(body.get("note")), null, null);
        emit(e, g, "requested", str(body.get("note")));
        // an AI proposal in a "trust" tenant is judged like a human draft; in an
        // "approve" tenant it always asks, envelope or not
        boolean ai = "ai".equals(g.get("origin"));
        if ("envelope".equals(mode()) && !(ai && "approve".equals(aiProposals()))) {
            Map<String, Object> verdict = envelopeVerdict(describe(e));
            if (verdict != null) {
                approveWith(e, g, "envelope", "pre-approved: inside envelope '" + verdict.get("ruleName") + "'"
                        + (verdict.get("message") == null ? "" : " — " + verdict.get("message")),
                        String.valueOf(verdict.get("ruleId")), String.valueOf(verdict.get("ruleName")));
            }
        }
        return view(e);
    }

    @Transactional
    public Map<String, Object> approve(String id, Map<String, Object> body) {
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
        Map<String, Object> g = state(e);
        if (g.get("readiness") == null) {
            g.put("readiness", readinessTemplate());
        }
        if (g.get("substanceHash") == null) {
            g.put("substanceHash", substanceHash(e));
        }
        approveWith(e, g, actor(), str(body.get("note")), null, null);
        return view(e);
    }

    @Transactional
    public Map<String, Object> reject(String id, Map<String, Object> body) {
        requireOn();
        requireApprover();
        ProductOffering e = load(id);
        Map<String, Object> g = state(e);
        e.setGovernanceState(REJECTED);
        e.setApprovalExpiresAt(null);
        g.put("rejectedAt", now());
        g.put("rejectedBy", actor());
        g.put("rejectedReason", str(body.get("note")));
        store(e, g);
        line(e, "rejected", str(body.get("note")), null, null);
        emit(e, g, "rejected", str(body.get("note")));
        return view(e);
    }

    /** Hold: any catalog writer may pull the brake (a hold is the safe direction). A dated hold
     *  pushes the window; an open-ended hold withdraws a live offer until someone resumes it. */
    @Transactional
    public Map<String, Object> hold(String id, Map<String, Object> body) {
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
        OffsetDateTime until = body.get("until") == null ? null : OffsetDateTime.parse(String.valueOf(body.get("until")));
        Map<String, Object> g = state(e);
        g.put("heldFrom", st);
        g.put("heldAt", now());
        g.put("heldBy", actor());
        g.put("holdReason", str(body.get("note")));
        g.put("holdUntil", until == null ? null : until.toString());
        if (LifecyclePolicy.launched(e.getLifecycleStatus())) {
            if (until != null) {
                g.put("heldValidFrom", e.getValidFrom() == null ? null : e.getValidFrom().toString());
                e.setValidFrom(until);
                e.setAnnouncedAt(null); // launch day fires again when the hold lifts
            } else {
                g.put("heldStatus", e.getLifecycleStatus());
                e.setLifecycleStatus("In test");
            }
        }
        e.setGovernanceState(HELD);
        e.setLaunchHoldUntil(until);
        store(e, g);
        line(e, "held", (until == null ? "held until resumed" : "held until " + until) + note(body), null, null);
        emit(e, g, "held", str(body.get("note")));
        return view(e);
    }

    @Transactional
    public Map<String, Object> resume(String id, Map<String, Object> body) {
        requireOn();
        ProductOffering e = load(id);
        if (!HELD.equals(entity(e))) {
            throw new BadRequestException("'" + e.getName() + "' is not on hold");
        }
        Map<String, Object> g = state(e);
        String back = g.get("heldFrom") == null ? APPROVED : String.valueOf(g.get("heldFrom"));
        if (g.get("heldStatus") != null) {
            e.setLifecycleStatus(String.valueOf(g.remove("heldStatus")));
        }
        if (g.containsKey("heldValidFrom")) {
            Object was = g.remove("heldValidFrom");
            OffsetDateTime restored = was == null ? OffsetDateTime.now() : OffsetDateTime.parse(String.valueOf(was));
            e.setValidFrom(restored.isAfter(OffsetDateTime.now()) ? restored : OffsetDateTime.now());
        }
        g.put("resumedAt", now());
        g.put("resumedBy", actor());
        g.put("holdUntil", null);
        e.setGovernanceState(back);
        e.setLaunchHoldUntil(null);
        store(e, g);
        line(e, "resumed", "back to " + back + note(body), null, null);
        emit(e, g, "resumed", str(body.get("note")));
        return view(e);
    }

    /** A readiness tick: the owner (or an approver) says their part is done. */
    @Transactional
    public Map<String, Object> ready(String id, Map<String, Object> body) {
        requireOn();
        ProductOffering e = load(id);
        String owner = str(body.get("owner"));
        if (owner == null) {
            throw new BadRequestException("owner (the readiness authority, e.g. campaign:write) is required");
        }
        if (!approver() && !holds(owner)) {
            throw new BadRequestException("only the " + owner + " owner or an approver can tick this readiness");
        }
        Map<String, Object> g = state(e);
        List<Map<String, Object>> readiness = readiness(g);
        Map<String, Object> hit = readiness.stream().filter(r -> owner.equals(r.get("owner"))).findFirst()
                .orElseThrow(() -> new BadRequestException("no readiness item is owned by " + owner));
        hit.put("done", !Boolean.FALSE.equals(body.getOrDefault("done", true)));
        hit.put("by", actor());
        hit.put("at", now());
        hit.put("note", str(body.get("note")));
        g.put("readiness", readiness);
        store(e, g);
        line(e, "ready", hit.get("label") + " — " + (Boolean.TRUE.equals(hit.get("done")) ? "done" : "reopened")
                + " by " + actor() + note(body), null, null);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("owner", owner);
        extra.put("done", hit.get("done"));
        emit(e, g, "ready", str(body.get("note")), extra);
        return view(e);
    }

    /** Launch now: approved + not held + ready (an approver may force past readiness). */
    @Transactional
    public Map<String, Object> launch(String id, Map<String, Object> body) {
        requireOn();
        ProductOffering e = load(id);
        boolean force = Boolean.TRUE.equals(body.get("force")) && approver();
        if (LAUNCHED.equals(entity(e)) && LifecyclePolicy.launched(e.getLifecycleStatus())) {
            return view(e);
        }
        requireLaunchable(e, force);
        Map<String, Object> g = state(e);
        if (body.get("channel") instanceof List<?> ch && !ch.isEmpty()) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (Object o : ch) {
                Map<String, Object> ref = new LinkedHashMap<>();
                String cid = o instanceof Map<?, ?> m ? String.valueOf(m.get("id")) : String.valueOf(o);
                ref.put("id", cid);
                Channels.REGISTERED.stream().filter(r -> r.get("id").equals(cid)).findFirst()
                        .ifPresent(r -> ref.put("name", r.get("name")));
                list.add(ref);
            }
            Channels.requireKnown(list);
            e.setChannelJson(mapper.writeJsonObjectList(list));
        }
        if (body.get("validFrom") != null) {
            e.setValidFrom(OffsetDateTime.parse(String.valueOf(body.get("validFrom"))));
        } else if (e.getValidFrom() == null || e.getValidFrom().isAfter(OffsetDateTime.now())) {
            e.setValidFrom(e.getValidFrom() == null ? OffsetDateTime.now() : e.getValidFrom());
        }
        if (body.get("validTo") != null) {
            e.setValidTo(OffsetDateTime.parse(String.valueOf(body.get("validTo"))));
        }
        e.setLifecycleStatus("Active");
        e.setAnnouncedAt(null);
        String skipped = force ? readiness(g).stream().filter(r -> !Boolean.TRUE.equals(r.get("done")))
                .map(r -> String.valueOf(r.get("label"))).reduce((a, b) -> a + ", " + b).orElse(null) : null;
        markLaunched(e, g, "launched by " + actor() + (skipped == null ? "" : " — forced past readiness: " + skipped)
                + note(body), null, null);
        return view(e);
    }

    /** Unlaunch: Retired with an end date — the trail keeps the launch, the shelf loses the offer. */
    @Transactional
    public Map<String, Object> unlaunch(String id, Map<String, Object> body) {
        requireOn();
        ProductOffering e = load(id);
        if (!LifecyclePolicy.launched(e.getLifecycleStatus())) {
            throw new BadRequestException("'" + e.getName() + "' is not launched");
        }
        OffsetDateTime end = body.get("endDate") == null ? OffsetDateTime.now()
                : OffsetDateTime.parse(String.valueOf(body.get("endDate")));
        Map<String, Object> g = state(e);
        e.setValidTo(end);
        if (!end.isAfter(OffsetDateTime.now())) {
            e.setLifecycleStatus("Retired");
        }
        e.setGovernanceState(NONE);
        e.setApprovalExpiresAt(null);
        g.put("unlaunchedAt", now());
        g.put("unlaunchedBy", actor());
        g.put("unlaunchEnd", end.toString());
        store(e, g);
        line(e, "unlaunched", "sales end " + end + note(body), null, null);
        emit(e, g, "unlaunched", str(body.get("note")));
        return view(e);
    }

    /** Envelope dry-run for a draft that may not be saved yet: would it launch by itself? */
    public Map<String, Object> dryRun(ProductOfferingDto dto) {
        Map<String, Object> ctx = describe(dto);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", mode());
        out.put("context", ctx);
        Map<String, Object> verdict = "envelope".equals(mode()) ? envelopeVerdict(ctx) : null;
        out.put("preApproved", verdict != null);
        if (verdict != null) {
            out.put("envelope", Map.of("id", String.valueOf(verdict.get("ruleId")),
                    "name", String.valueOf(verdict.get("ruleName")),
                    "message", verdict.get("message") == null ? "" : String.valueOf(verdict.get("message"))));
        }
        out.put("verdict", verdict != null ? "launches by itself — inside envelope '" + verdict.get("ruleName") + "'"
                : NONE.equals(mode()) ? "launch governance is off for this tenant — a write is a launch"
                : "always".equals(mode()) ? "needs an approver — this tenant approves every launch"
                : "needs an approver — outside every envelope");
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> view(String id) {
        return view(load(id));
    }

    /** The approvals desk: everything waiting on a decision, a tick or a resume. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> queue() {
        List<Map<String, Object>> out = new ArrayList<>();
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
        out.sort((a, b) -> String.valueOf(b.get("lastUpdate")).compareTo(String.valueOf(a.get("lastUpdate"))));
        return out;
    }

    /* ---------- the clock (called from the launch tick, already acting as the tenant) ---------- */

    public void tick(ProductOffering e, OffsetDateTime now) {
        String st = e.getGovernanceState();
        if (APPROVED.equals(st) && e.getApprovalExpiresAt() != null && e.getApprovalExpiresAt().isBefore(now)) {
            Map<String, Object> g = state(e);
            e.setGovernanceState(EXPIRED);
            g.put("expiredAt", now.toString());
            store(e, g);
            offerings.save(e);
            line(e, "expired", "approval expired on " + e.getApprovalExpiresAt() + " — request again", null, null);
            emit(e, g, "expired", null);
        } else if (HELD.equals(st) && e.getLaunchHoldUntil() != null && e.getLaunchHoldUntil().isBefore(now)
                && LifecyclePolicy.launched(e.getLifecycleStatus())) {
            Map<String, Object> g = state(e);
            g.put("resumedAt", now.toString());
            g.put("resumedBy", "clock");
            g.remove("heldValidFrom");
            e.setGovernanceState(LAUNCHED);
            e.setLaunchHoldUntil(null);
            store(e, g);
            offerings.save(e);
            line(e, "resumed", "hold elapsed — back on sale", null, null);
            emit(e, g, "resumed", "hold elapsed");
        }
    }

    /* ---------- the envelope context ---------- */

    public Map<String, Object> describe(ProductOffering e) {
        return describe(mapper.toDto(e));
    }

    /** What an envelope may look at: plain facts, in plain units. */
    public Map<String, Object> describe(ProductOfferingDto dto) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("name", dto.getName());
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
        ctx.put("category", categories);
        ctx.put("categoryId", categoryIds);
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
            Map<String, Object> price = readObject(p.getPriceJson());
            Double value = price.get("value") == null ? null : Double.valueOf(String.valueOf(price.get("value")));
            if (value == null) {
                continue;
            }
            if (currency == null && price.get("unit") != null) {
                currency = String.valueOf(price.get("unit"));
            }
            if ("recurring".equalsIgnoreCase(p.getPriceType())) {
                recurring = recurring == null ? value : Math.min(recurring, value);
            } else {
                oneTime = oneTime == null ? value : Math.min(oneTime, value);
            }
        }
        ctx.put("price", recurring != null ? recurring : oneTime != null ? oneTime : 0);
        ctx.put("priceType", recurring != null ? "recurring" : oneTime != null ? "oneTime" : "none");
        ctx.put("currency", currency);
        // spec characteristics: allowance and validity, parsed to numbers
        Map<String, Object> chars = new LinkedHashMap<>();
        if (dto.getProductSpecification() != null && dto.getProductSpecification().get("id") != null) {
            ProductSpecification spec = specs.findByIdAndTenantId(String.valueOf(dto.getProductSpecification().get("id")),
                    scope.currentTenantId()).orElse(null);
            if (spec != null) {
                ctx.put("specification", spec.getName());
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
        ctx.put("chars", chars);
        ctx.put("allowanceGb", gigabytes(firstOf(chars, "Data", "data", "allowance", "Allowance", "dataAllowance")));
        ctx.put("validityDays", days(firstOf(chars, "Validity", "validity", "validityDays")));
        ctx.put("zeroRatedApps", firstOf(chars, "zeroRatedApps", "Zero-rated apps") != null);
        List<String> channels = new ArrayList<>();
        for (Map<String, Object> c : nullSafe(dto.getChannel())) {
            if (c.get("id") != null) {
                channels.add(String.valueOf(c.get("id")));
            }
        }
        if (channels.isEmpty()) {
            Channels.REGISTERED.forEach(r -> channels.add(r.get("id")));
        }
        ctx.put("channel", channels);
        ctx.put("channelCount", channels.size());
        List<String> terms = new ArrayList<>();
        for (Map<String, Object> t : nullSafe(dto.getProductOfferingTerm())) {
            if (t.get("name") != null) {
                terms.add(String.valueOf(t.get("name")).toLowerCase(Locale.ROOT));
            }
        }
        ctx.put("term", terms);
        ctx.put("isBundle", Boolean.TRUE.equals(dto.getIsBundle()));
        return ctx;
    }

    private Map<String, Object> envelopeVerdict(Map<String, Object> ctx) {
        Map<String, Object> v = policy.evaluateRaw("launch", ctx);
        if (v == null || !"allow".equals(v.get("decision")) || v.get("ruleId") == null) {
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
        Map<String, Object> g = state(e);
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
            List<String> open = readiness(g).stream().filter(r -> !Boolean.TRUE.equals(r.get("done")))
                    .map(r -> String.valueOf(r.get("label"))).toList();
            if (!open.isEmpty() && !force) {
                throw new BadRequestException("'" + e.getName() + "' is approved but not ready: " + String.join(", ", open)
                        + (approver() ? " — an approver may launch anyway with force" : ""));
            }
            return;
        }
        if (approver()) {
            // the approver's one-stroke path: approve and launch in the same breath
            if (g.get("readiness") == null) {
                g.put("readiness", readinessTemplate());
            }
            g.put("substanceHash", substanceHash(e));
            approveWith(e, g, actor(), "approved directly by " + actor(), null, null);
            return;
        }
        throw new BadRequestException("'" + e.getName() + "' needs launch approval (state: " + st + ", tenant mode: "
                + mode() + ") — request it: POST productOffering/" + e.getId() + "/governance/request");
    }

    private void approveWith(ProductOffering e, Map<String, Object> g, String by, String note, String envelopeId, String envelopeName) {
        e.setGovernanceState(APPROVED);
        e.setApprovalExpiresAt(OffsetDateTime.now().plusDays(expiryDays()));
        g.put("approvedAt", now());
        g.put("approvedBy", by);
        g.put("approvalNote", note);
        if (envelopeId != null) {
            g.put("envelope", Map.of("id", envelopeId, "name", envelopeName));
        } else {
            g.remove("envelope");
        }
        store(e, g);
        line(e, "approved", note, envelopeId, envelopeName);
        emit(e, g, "approved", note);
    }

    private void markLaunched(ProductOffering e, Map<String, Object> g, String note, String envelopeId, String envelopeName) {
        e.setGovernanceState(LAUNCHED);
        e.setLaunchHoldUntil(null);
        g.put("launchedAt", now());
        g.put("launchedBy", actor());
        g.put("launchedChannels", readList(e.getChannelJson()).stream().map(c -> String.valueOf(c.get("id"))).toList());
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

    public Map<String, Object> state(ProductOffering e) {
        return readObject(e.getGovernanceJson());
    }

    private void store(ProductOffering e, Map<String, Object> g) {
        try {
            e.setGovernanceJson(json.writeValueAsString(g));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        e.setLastUpdate(OffsetDateTime.now());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readiness(Map<String, Object> g) {
        if (g.get("readiness") instanceof List<?> l) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : l) {
                if (o instanceof Map<?, ?> m) {
                    out.add(new LinkedHashMap<>((Map<String, Object>) m));
                }
            }
            return out;
        }
        return new ArrayList<>();
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

    private void emit(ProductOffering e, Map<String, Object> g, String action, String note) {
        emit(e, g, action, note, Map.of());
    }

    private void emit(ProductOffering e, Map<String, Object> g, String action, String note, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", e.getId());
        body.put("name", e.getName());
        body.put("action", action);
        body.put("state", e.getGovernanceState());
        body.put("lifecycleStatus", e.getLifecycleStatus());
        body.put("actor", actor());
        body.put("note", note);
        body.put("readiness", g.get("readiness"));
        body.put("envelope", g.get("envelope"));
        body.put("holdUntil", e.getLaunchHoldUntil() == null ? null : e.getLaunchHoldUntil().toString());
        body.put("approvalExpiresAt", e.getApprovalExpiresAt() == null ? null : e.getApprovalExpiresAt().toString());
        body.put("validFrom", e.getValidFrom() == null ? null : e.getValidFrom().toString());
        body.putAll(extra);
        events.publish(EVENT, "productOffering", body);
        log.info("governance {}: '{}' -> {} ({})", action, e.getName(), e.getGovernanceState(), note);
    }

    private Map<String, Object> view(ProductOffering e) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", e.getId());
        v.put("name", e.getName());
        v.put("lifecycleStatus", e.getLifecycleStatus());
        v.put("validFrom", e.getValidFrom() == null ? null : e.getValidFrom().toString());
        v.put("validTo", e.getValidTo() == null ? null : e.getValidTo().toString());
        v.put("governanceState", entity(e));
        v.put("mode", mode());
        v.put("holdUntil", e.getLaunchHoldUntil() == null ? null : e.getLaunchHoldUntil().toString());
        v.put("approvalExpiresAt", e.getApprovalExpiresAt() == null ? null : e.getApprovalExpiresAt().toString());
        v.put("channel", readList(e.getChannelJson()));
        v.put("lastUpdate", e.getLastUpdate() == null ? "" : e.getLastUpdate().toString());
        v.putAll(state(e));
        v.put("readiness", readiness(state(e)));
        List<Map<String, Object>> trail = new ArrayList<>();
        for (GovernanceLedger l : ledger.findAllByTenantIdAndOfferingIdOrderByAtAsc(e.getTenantId(), e.getId())) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("action", l.getAction());
            t.put("actor", l.getActor());
            t.put("note", l.getNote());
            t.put("envelopeId", l.getEnvelopeId());
            t.put("envelopeName", l.getEnvelopeName());
            t.put("at", l.getAt().toString());
            trail.add(t);
        }
        v.put("ledger", trail);
        v.put("canApprove", approver());
        return v;
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

    private Map<String, Object> readObject(String s) {
        if (s == null || s.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return json.readValue(s, JSON_OBJECT);
        } catch (Exception ex) {
            return new LinkedHashMap<>();
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

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String note(Map<String, Object> body) {
        return body.get("note") == null ? "" : " — " + body.get("note");
    }
}
