package com.bss.policy.service;

import com.bss.policy.api.ApiConstants;
import com.bss.policy.api.OffsetPageRequest;
import com.bss.policy.api.PagedResult;
import com.bss.policy.dto.DecisionView;
import com.bss.policy.dto.ExperienceView;
import com.bss.policy.dto.PolicyRulePatch;
import com.bss.policy.dto.PolicyRuleRequest;
import com.bss.policy.dto.PolicyRuleView;
import com.bss.policy.dto.PriceResult;
import com.bss.policy.dto.PriceResult.Adjustment;
import com.bss.policy.dto.ReferencingRule;
import com.bss.policy.dto.Teaser;
import com.bss.policy.engine.PolicyEngine;
import com.bss.policy.entity.PolicyRule;
import com.bss.policy.events.DomainEventPublisher;
import com.bss.policy.exception.BadRequestException;
import com.bss.policy.exception.NotFoundException;
import com.bss.policy.repository.PolicyRuleRepository;
import com.bss.policy.security.TenantScope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CRUD over policy rules (authored as data) plus the decision endpoint that
 * order orchestration calls. Evaluation walks the enabled rules for a domain
 * in priority order and returns the first DENY whose condition matches.
 */
@Service
public class PolicyService {

    private static final Logger log = LoggerFactory.getLogger(PolicyService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final PolicyRuleRepository repository;
    private final PolicyEngine engine;
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;
    private final com.bss.policy.security.TenantRegistry tenants;

    public PolicyService(PolicyRuleRepository repository, PolicyEngine engine,
            DomainEventPublisher events, TenantScope tenantScope,
            com.bss.policy.security.TenantRegistry tenants) {
        this.tenants = tenants;
        this.repository = repository;
        this.engine = engine;
        this.events = events;
        this.tenantScope = tenantScope;
    }

    // ---- decision ----

    /**
     * The personalization decision: first ENABLED rule in domain
     * 'personalization' whose condition matches the visitor context wins —
     * its `experience` payload tells the channel what to show, its
     * `message` is the banner copy. No match means "no opinion": the
     * caller's coded default applies.
     */
    @Transactional(readOnly = true)
    public ExperienceView experienceFor(Map<String, Object> context) {
        for (PolicyRule rule : repository.findByDomainAndEnabledTrueOrderByPriorityAsc("personalization")) {
            if (engine.matches(rule.getCondition(), context)) {
                return new ExperienceView(rule.getId(), rule.getName(), rule.getMessage(),
                        rule.getExperience() == null ? null : fromJson(rule.getExperience()));
            }
        }
        return ExperienceView.NONE;
    }

    /** The operator's document as stored: JSON parses to its tree, legacy free text renders as-is. */
    private static JsonNode fromJson(String json) {
        try {
            return JSON.readTree(json);
        } catch (JsonProcessingException e) {
            return new TextNode(json);
        }
    }

    /** The order pipeline's question: given this context, allow or deny? */
    public Decision evaluate(String domain, Map<String, Object> context) {
        String d = (domain == null || domain.isBlank()) ? "order" : domain;
        List<PolicyRule> rules = repository.findByDomainAndEnabledTrueOrderByPriorityAsc(d);
        for (PolicyRule rule : rules) {
            if (!engine.matches(rule.getCondition(), context)) {
                continue;
            }
            if ("deny".equalsIgnoreCase(rule.getEffect())) {
                log.info("policy deny: rule '{}' ({}) matched at domain '{}'", rule.getName(), rule.getId(), d);
                return Decision.deny(rule);
            }
            if ("allow".equalsIgnoreCase(rule.getEffect())) {
                // a matching ALLOW rule is a named permission — launch envelopes
                // ("inside this envelope, no approval needed") read the rule back
                log.info("policy allow: rule '{}' ({}) matched at domain '{}'", rule.getName(), rule.getId(), d);
                return Decision.allowBy(rule);
            }
        }
        return Decision.allow();
    }

    /** The domain form of a decision; {@link #view()} is its wire form. */
    public record Decision(boolean allowed, String ruleId, String ruleName, String message) {
        static Decision allow() {
            return new Decision(true, null, null, null);
        }

        static Decision allowBy(PolicyRule rule) {
            return new Decision(true, rule.getId(), rule.getName(), rule.getMessage());
        }

        static Decision deny(PolicyRule rule) {
            String msg = (rule.getMessage() == null || rule.getMessage().isBlank())
                    ? "This order is not permitted by a business rule (" + rule.getName() + ")."
                    : rule.getMessage();
            return new Decision(false, rule.getId(), rule.getName(), msg);
        }

        public DecisionView view() {
            String decision = allowed ? "allow" : "deny";
            return !allowed || ruleId != null
                    ? new DecisionView.ByRule(decision, ruleId, ruleName, message)
                    : DecisionView.Allowed.INSTANCE;
        }
    }

    // ---- pricing (dynamic price adjustments) ----

    /**
     * A pricing rule is also MARKETING: the shop advertises "buy these
     * together and save" on the product page. Teasers are the enabled
     * pricing rules that mention the offering, reduced to their public face
     * (message + audience) — safe for anonymous eyes, no conditions leaked.
     * Audience derives from the condition: a rule that requires the absence
     * of organizationId is consumer-only; one that requires it is business.
     */
    @Transactional(readOnly = true)
    public List<Teaser> teasers(String offeringId) {
        List<Teaser> teasers = new ArrayList<>();
        if (offeringId == null || offeringId.isBlank()) {
            return teasers;
        }
        for (PolicyRule rule : repository.enabledPricingRules()) {
            String condition = rule.getCondition();
            if (condition == null || !condition.contains(offeringId)) {
                continue;
            }
            String audience = "all";
            if (condition.contains("{\"!\":{\"var\":\"organizationId\"}}")) {
                audience = "consumer";
            } else if (condition.contains("\"organizationId\"")) {
                audience = "business";
            }
            // the OTHER offerings in the condition — the shop links "add it"
            List<String> related = new ArrayList<>();
            java.util.regex.Matcher ids = java.util.regex.Pattern
                    .compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                    .matcher(condition);
            while (ids.find()) {
                String id = ids.group();
                if (!id.equals(offeringId) && !related.contains(id)) {
                    related.add(id);
                }
            }
            teasers.add(new Teaser(rule.getName(), rule.getMessage() == null ? rule.getName() : rule.getMessage(),
                    audience, rule.getAdjustmentType(), rule.getAdjustmentValue(), related));
        }
        return teasers;
    }

    /**
     * The read-back an offering never had: every rule that NAMES this
     * offering — pricing and blocking, enabled and DISABLED, each with its
     * state. A rule can apply to a basket, a company or a customer type, so it
     * cannot live on one offering; the attachment is therefore one-way in the
     * model, and this is the reverse read.
     *
     * Deliberately NOT {@link #teasers(String)}. That is the anonymous shop
     * window, so it walks the enabled PRICING rules and hands out marketing
     * copy only. This is back-office configuration behind {@code policy:read}:
     * a quantity cap, an incompatibility, and above all a rule somebody
     * switched off, which is the one thing you need when you are asking why
     * nothing is happening — or what still points here before you retire it.
     *
     * A blank offering id matches nothing rather than everything.
     */
    @Transactional(readOnly = true)
    public List<ReferencingRule> rulesReferencing(String offeringId) {
        if (offeringId == null || offeringId.isBlank()) {
            return List.of();
        }
        List<ReferencingRule> rules = new ArrayList<>();
        for (PolicyRule rule : repository.findByConditionContainingOrderByPriorityAsc(offeringId)) {
            rules.add(ReferencingRule.of(rule));
        }
        return rules;
    }

    /**
     * Apply the enabled pricing rules to a base subtotal in context, in priority
     * order. Every matching rule contributes an adjustment (percent of the
     * running subtotal, or a fixed amount); the running subtotal compounds so
     * two 10%-off rules stack multiplicatively, as a customer would expect.
     */
    @Transactional(readOnly = true)
    public PriceResult price(Map<String, Object> context) {
        return price(context, false);
    }

    /**
     * The GUEST's price preview. The context is SANITIZED, not the rules:
     * identity claims are stripped from whatever the anonymous caller sent,
     * so a negotiated company deal can never fire (or leak its label) no
     * matter what a guest posts — while consumer-scoped rules, which merely
     * require the ABSENCE of a company, preview correctly. Sign-in reprices
     * with the genuine identity context.
     */
    @Transactional(readOnly = true)
    public PriceResult indicative(Map<String, Object> context) {
        Map<String, Object> anonymous = new java.util.LinkedHashMap<>(context);
        for (String var : IDENTITY_VARS) {
            anonymous.remove(var);
        }
        return price(anonymous, false).asIndicative();
    }

    private static final List<String> IDENTITY_VARS =
            List.of("organizationId", "memberCount", "verifiedIdentity", "party");

    private PriceResult price(Map<String, Object> context, boolean identityFreeOnly) {
        BigDecimal base = money(context.get("subtotal"));
        BigDecimal running = base;
        List<Adjustment> adjustments = new ArrayList<>();
        for (PolicyRule rule : repository.enabledPricingRules()) {
            if (identityFreeOnly && rule.getCondition() != null
                    && IDENTITY_VARS.stream().anyMatch(v -> rule.getCondition().contains("\"" + v + "\""))) {
                continue;
            }
            if (!engine.matches(rule.getCondition(), context) || rule.getAdjustmentType() == null
                    || rule.getAdjustmentValue() == null) {
                continue;
            }
            BigDecimal delta;
            if ("percent".equalsIgnoreCase(rule.getAdjustmentType())) {
                delta = running.multiply(rule.getAdjustmentValue())
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            } else {
                delta = rule.getAdjustmentValue().setScale(2, RoundingMode.HALF_UP);
            }
            running = running.add(delta);
            adjustments.add(new Adjustment(rule.getId(), rule.getName(),
                    rule.getMessage() == null || rule.getMessage().isBlank() ? rule.getName() : rule.getMessage(),
                    rule.getAdjustmentType(), rule.getAdjustmentValue(), delta));
            log.info("pricing rule '{}' applied: {} {}", rule.getName(), rule.getAdjustmentType(), delta);
        }
        if (running.signum() < 0) {
            running = BigDecimal.ZERO;
        }
        return PriceResult.of(base, adjustments, running.setScale(2, RoundingMode.HALF_UP));
    }

    private static BigDecimal money(Object o) {
        if (o instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        try {
            return o == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    // ---- CRUD ----

    @Transactional(readOnly = true)
    public PagedResult<PolicyRuleView> list(long offset, int limit) {
        List<PolicyRuleView> items = new ArrayList<>();
        for (PolicyRule rule : repository.findAllByOrderByPriorityAsc(new OffsetPageRequest(offset, limit))) {
            items.add(view(rule));
        }
        return new PagedResult<>(items, repository.count());
    }

    @Transactional(readOnly = true)
    public PolicyRuleView get(String id) {
        return view(repository.findById(id).orElseThrow(() -> new NotFoundException("policy rule '" + id + "' not found")));
    }

    /** PRICE-PARITY MODE: a channel-conditioned PRICING rule is only legal
     *  when the tenant explicitly chose per-channel pricing. Uniform (the
     *  default) means one price everywhere — the switch has teeth, and the
     *  proof face can attest whichever policy the tenant runs. */
    private void requireParityAllows(String domain, String condition) {
        if (!"pricing".equals(domain) || condition == null || !condition.contains("\"channel\"")) {
            return;
        }
        var entry = tenants.byId(tenantScope.currentTenantId());
        String mode = entry == null || entry.getPriceParityMode() == null
                ? "uniform" : entry.getPriceParityMode();
        if (!"per-channel".equalsIgnoreCase(mode)) {
            throw new BadRequestException("this operator runs UNIFORM price parity — "
                    + "channel-conditioned pricing rules are refused; flip "
                    + "price-parity-mode to per-channel first");
        }
    }

    @Transactional
    public PolicyRuleView create(PolicyRuleRequest body) {
        String name = body.name();
        String condition = text(body.condition());
        if (name == null || name.isBlank()) {
            throw new BadRequestException("name is required");
        }
        if (condition == null || condition.isBlank()) {
            throw new BadRequestException("condition (a JSON-logic expression) is required");
        }
        validateCondition(condition);
        requireParityAllows(body.domain(), condition);

        PolicyRule rule = new PolicyRule();
        String id = UUID.randomUUID().toString();
        rule.setId(id);
        rule.setHref(ApiConstants.BASE_PATH + "/policyRule/" + id);
        rule.setTenantId(tenantScope.currentTenantId());
        rule.setName(name);
        rule.setDescription(body.description());
        rule.setDomain(orDefault(body.domain(), "order"));
        rule.setEffect(orDefault(body.effect(), "deny"));
        rule.setPriority(body.priority() == null ? 100 : body.priority());
        rule.setEnabled(body.enabled() == null || body.enabled());
        rule.setCondition(condition);
        rule.setMessage(body.message());
        rule.setAdjustmentType(body.adjustmentType());
        rule.setAdjustmentValue(decimal(body.adjustmentValue()));
        rule.setExperience(text(body.experience()));
        OffsetDateTime now = OffsetDateTime.now();
        rule.setCreatedAt(now);
        rule.setLastUpdate(now);

        PolicyRuleView dto = view(repository.save(rule));
        events.publish("PolicyRuleCreateEvent", "policyRule", dto);
        return dto;
    }

    @Transactional
    public PolicyRuleView patch(String id, PolicyRulePatch body) {
        PolicyRule rule = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("policy rule '" + id + "' not found"));
        if (body.name() != null) {
            rule.setName(text(body.name()));
        }
        if (body.description() != null) {
            rule.setDescription(text(body.description()));
        }
        if (body.domain() != null) {
            rule.setDomain(orDefault(text(body.domain()), "order"));
        }
        if (body.effect() != null) {
            rule.setEffect(orDefault(text(body.effect()), "deny"));
        }
        if (body.priority() != null) {
            rule.setPriority(asInt(body.priority(), rule.getPriority()));
        }
        if (body.enabled() != null) {
            rule.setEnabled(asBool(body.enabled()));
        }
        if (body.condition() != null) {
            String condition = text(body.condition());
            requireParityAllows(rule.getDomain(), condition);
            validateCondition(condition);
            rule.setCondition(condition);
        }
        if (body.message() != null) {
            rule.setMessage(text(body.message()));
        }
        if (body.experience() != null) {
            rule.setExperience(text(body.experience()));
        }
        if (body.adjustmentType() != null) {
            rule.setAdjustmentType(text(body.adjustmentType()));
        }
        if (body.adjustmentValue() != null) {
            rule.setAdjustmentValue(decimal(body.adjustmentValue()));
        }
        rule.setLastUpdate(OffsetDateTime.now());
        PolicyRuleView dto = view(repository.save(rule));
        events.publish("PolicyRuleAttributeValueChangeEvent", "policyRule", dto);
        return dto;
    }

    @Transactional
    public void delete(String id) {
        if (!repository.existsById(id)) {
            throw new NotFoundException("policy rule '" + id + "' not found");
        }
        repository.deleteById(id);
    }

    private void validateCondition(String condition) {
        try {
            JSON.readTree(condition);
        } catch (Exception e) {
            throw new BadRequestException("condition must be valid JSON (a JSON-logic expression)");
        }
    }

    private static PolicyRuleView view(PolicyRule rule) {
        return PolicyRuleView.of(rule, rule.getExperience() == null ? null : fromJson(rule.getExperience()));
    }

    /**
     * A posted tree as the text the column stores: a JSON string as itself,
     * JSON null as absent, any other document (the operator's condition or
     * experience object, a number) as its JSON.
     */
    private static String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.isTextual() ? node.asText() : node.toString();
    }

    private static String orDefault(String v, String def) {
        return (v == null || v.isBlank()) ? def : v;
    }

    private static int asInt(JsonNode node, int def) {
        if (node.isNumber()) {
            return node.intValue();
        }
        try {
            return node.isNull() ? def : Integer.parseInt(node.asText().trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean asBool(JsonNode node) {
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        return "true".equalsIgnoreCase(node.asText());
    }

    /** Read as the map was: a JSON number through its double ({@code 10} → {@code 10.0}), a string exactly. */
    private static BigDecimal decimal(JsonNode node) {
        if (node == null || node.isNull() || node.asText().isBlank()) {
            return null;
        }
        if (node.isNumber()) {
            return BigDecimal.valueOf(node.doubleValue());
        }
        try {
            return new BigDecimal(node.asText().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
