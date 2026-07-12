package com.bss.policy.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Default rule engine: rules are JSON-logic, evaluated as pure data (no code
 * execution). Selected by {@code bss.policy.engine=json-logic} (the default).
 */
@Component
@ConditionalOnProperty(name = "bss.policy.engine", havingValue = "json-logic", matchIfMissing = true)
public class JsonLogicPolicyEngine implements PolicyEngine {

    private static final Logger log = LoggerFactory.getLogger(JsonLogicPolicyEngine.class);

    private final ObjectMapper mapper;

    public JsonLogicPolicyEngine(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean matches(String condition, Map<String, Object> context) {
        if (condition == null || condition.isBlank()) {
            return false;
        }
        try {
            Object logic = mapper.readValue(condition, Object.class);
            return JsonLogic.test(logic, context);
        } catch (Exception e) {
            // A malformed rule is inert, not fatal: log and treat as non-matching
            // so a bad rule never blocks ordering.
            log.warn("policy rule condition is not valid JSON-logic, ignoring: {}", e.getMessage());
            return false;
        }
    }
}
