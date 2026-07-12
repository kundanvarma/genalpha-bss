package com.bss.policy.service;

import com.bss.policy.api.ApiConstants;
import com.bss.policy.api.OffsetPageRequest;
import com.bss.policy.api.PagedResult;
import com.bss.policy.engine.PolicyEngine;
import com.bss.policy.entity.PolicyRule;
import com.bss.policy.events.DomainEventPublisher;
import com.bss.policy.exception.BadRequestException;
import com.bss.policy.exception.NotFoundException;
import com.bss.policy.repository.PolicyRuleRepository;
import com.bss.policy.security.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    private final PolicyRuleRepository repository;
    private final PolicyEngine engine;
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;

    public PolicyService(PolicyRuleRepository repository, PolicyEngine engine,
            DomainEventPublisher events, TenantScope tenantScope) {
        this.repository = repository;
        this.engine = engine;
        this.events = events;
        this.tenantScope = tenantScope;
    }

    // ---- decision ----

    /** The order pipeline's question: given this context, allow or deny? */
    @Transactional(readOnly = true)
    public Decision evaluate(String domain, Map<String, Object> context) {
        String d = (domain == null || domain.isBlank()) ? "order" : domain;
        List<PolicyRule> rules = repository.findByDomainAndEnabledTrueOrderByPriorityAsc(d);
        for (PolicyRule rule : rules) {
            if (engine.matches(rule.getCondition(), context) && "deny".equalsIgnoreCase(rule.getEffect())) {
                log.info("policy deny: rule '{}' ({}) matched at domain '{}'", rule.getName(), rule.getId(), d);
                return Decision.deny(rule);
            }
        }
        return Decision.allow();
    }

    public record Decision(boolean allowed, String ruleId, String ruleName, String message) {
        static Decision allow() {
            return new Decision(true, null, null, null);
        }

        static Decision deny(PolicyRule rule) {
            String msg = (rule.getMessage() == null || rule.getMessage().isBlank())
                    ? "This order is not permitted by a business rule (" + rule.getName() + ")."
                    : rule.getMessage();
            return new Decision(false, rule.getId(), rule.getName(), msg);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("decision", allowed ? "allow" : "deny");
            if (!allowed) {
                m.put("ruleId", ruleId);
                m.put("ruleName", ruleName);
                m.put("message", message);
            }
            return m;
        }
    }

    // ---- pricing (dynamic price adjustments) ----

    /**
     * Apply the enabled pricing rules to a base subtotal in context, in priority
     * order. Every matching rule contributes an adjustment (percent of the
     * running subtotal, or a fixed amount); the running subtotal compounds so
     * two 10%-off rules stack multiplicatively, as a customer would expect.
     */
    @Transactional(readOnly = true)
    public PriceResult price(Map<String, Object> context) {
        java.math.BigDecimal base = money(context.get("subtotal"));
        java.math.BigDecimal running = base;
        List<Map<String, Object>> adjustments = new ArrayList<>();
        for (PolicyRule rule : repository.enabledPricingRules()) {
            if (!engine.matches(rule.getCondition(), context) || rule.getAdjustmentType() == null
                    || rule.getAdjustmentValue() == null) {
                continue;
            }
            java.math.BigDecimal delta;
            if ("percent".equalsIgnoreCase(rule.getAdjustmentType())) {
                delta = running.multiply(rule.getAdjustmentValue())
                        .divide(java.math.BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
            } else {
                delta = rule.getAdjustmentValue().setScale(2, java.math.RoundingMode.HALF_UP);
            }
            running = running.add(delta);
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("ruleId", rule.getId());
            a.put("ruleName", rule.getName());
            a.put("label", rule.getMessage() == null || rule.getMessage().isBlank() ? rule.getName() : rule.getMessage());
            a.put("type", rule.getAdjustmentType());
            a.put("value", rule.getAdjustmentValue());
            a.put("amount", delta);
            adjustments.add(a);
            log.info("pricing rule '{}' applied: {} {}", rule.getName(), rule.getAdjustmentType(), delta);
        }
        if (running.signum() < 0) {
            running = java.math.BigDecimal.ZERO;
        }
        return new PriceResult(base, adjustments, running.setScale(2, java.math.RoundingMode.HALF_UP));
    }

    public record PriceResult(java.math.BigDecimal basePrice, List<Map<String, Object>> adjustments,
            java.math.BigDecimal total) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("basePrice", basePrice);
            m.put("adjustments", adjustments);
            m.put("total", total);
            return m;
        }
    }

    private static java.math.BigDecimal money(Object o) {
        if (o instanceof Number n) {
            return java.math.BigDecimal.valueOf(n.doubleValue());
        }
        try {
            return o == null ? java.math.BigDecimal.ZERO : new java.math.BigDecimal(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return java.math.BigDecimal.ZERO;
        }
    }

    // ---- CRUD ----

    @Transactional(readOnly = true)
    public PagedResult<Map<String, Object>> list(long offset, int limit) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (PolicyRule rule : repository.findAllByOrderByPriorityAsc(new OffsetPageRequest(offset, limit))) {
            items.add(toDto(rule));
        }
        return new PagedResult<>(items, repository.count());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        return toDto(repository.findById(id).orElseThrow(() -> new NotFoundException("policy rule '" + id + "' not found")));
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> body) {
        String name = str(body.get("name"));
        String condition = str(body.get("condition"));
        if (name == null || name.isBlank()) {
            throw new BadRequestException("name is required");
        }
        if (condition == null || condition.isBlank()) {
            throw new BadRequestException("condition (a JSON-logic expression) is required");
        }
        validateCondition(condition);

        PolicyRule rule = new PolicyRule();
        String id = UUID.randomUUID().toString();
        rule.setId(id);
        rule.setHref(ApiConstants.BASE_PATH + "/policyRule/" + id);
        rule.setTenantId(tenantScope.currentTenantId());
        rule.setName(name);
        rule.setDescription(str(body.get("description")));
        rule.setDomain(orDefault(str(body.get("domain")), "order"));
        rule.setEffect(orDefault(str(body.get("effect")), "deny"));
        rule.setPriority(asInt(body.get("priority"), 100));
        rule.setEnabled(body.get("enabled") == null || asBool(body.get("enabled")));
        rule.setCondition(condition);
        rule.setMessage(str(body.get("message")));
        rule.setAdjustmentType(str(body.get("adjustmentType")));
        rule.setAdjustmentValue(decimal(body.get("adjustmentValue")));
        OffsetDateTime now = OffsetDateTime.now();
        rule.setCreatedAt(now);
        rule.setLastUpdate(now);

        PolicyRule saved = repository.save(rule);
        Map<String, Object> dto = toDto(saved);
        events.publish("PolicyRuleCreateEvent", "policyRule", dto);
        return dto;
    }

    @Transactional
    public Map<String, Object> patch(String id, Map<String, Object> body) {
        PolicyRule rule = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("policy rule '" + id + "' not found"));
        if (body.containsKey("name")) {
            rule.setName(str(body.get("name")));
        }
        if (body.containsKey("description")) {
            rule.setDescription(str(body.get("description")));
        }
        if (body.containsKey("domain")) {
            rule.setDomain(orDefault(str(body.get("domain")), "order"));
        }
        if (body.containsKey("effect")) {
            rule.setEffect(orDefault(str(body.get("effect")), "deny"));
        }
        if (body.containsKey("priority")) {
            rule.setPriority(asInt(body.get("priority"), rule.getPriority()));
        }
        if (body.containsKey("enabled")) {
            rule.setEnabled(asBool(body.get("enabled")));
        }
        if (body.containsKey("condition")) {
            String condition = str(body.get("condition"));
            validateCondition(condition);
            rule.setCondition(condition);
        }
        if (body.containsKey("message")) {
            rule.setMessage(str(body.get("message")));
        }
        if (body.containsKey("adjustmentType")) {
            rule.setAdjustmentType(str(body.get("adjustmentType")));
        }
        if (body.containsKey("adjustmentValue")) {
            rule.setAdjustmentValue(decimal(body.get("adjustmentValue")));
        }
        rule.setLastUpdate(OffsetDateTime.now());
        Map<String, Object> dto = toDto(repository.save(rule));
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
            new com.fasterxml.jackson.databind.ObjectMapper().readValue(condition, Object.class);
        } catch (Exception e) {
            throw new BadRequestException("condition must be valid JSON (a JSON-logic expression)");
        }
    }

    private Map<String, Object> toDto(PolicyRule rule) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rule.getId());
        m.put("href", rule.getHref());
        m.put("name", rule.getName());
        m.put("description", rule.getDescription());
        m.put("domain", rule.getDomain());
        m.put("effect", rule.getEffect());
        m.put("priority", rule.getPriority());
        m.put("enabled", rule.isEnabled());
        m.put("condition", rule.getCondition());
        m.put("message", rule.getMessage());
        m.put("adjustmentType", rule.getAdjustmentType());
        m.put("adjustmentValue", rule.getAdjustmentValue());
        m.put("lastUpdate", rule.getLastUpdate());
        m.put("@type", "PolicyRule");
        return m;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String orDefault(String v, String def) {
        return (v == null || v.isBlank()) ? def : v;
    }

    private static int asInt(Object o, int def) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return o == null ? def : Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean asBool(Object o) {
        if (o instanceof Boolean b) {
            return b;
        }
        return "true".equalsIgnoreCase(String.valueOf(o));
    }

    private static java.math.BigDecimal decimal(Object o) {
        if (o == null || String.valueOf(o).isBlank()) {
            return null;
        }
        if (o instanceof Number n) {
            return java.math.BigDecimal.valueOf(n.doubleValue());
        }
        try {
            return new java.math.BigDecimal(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
