package com.bss.policy.engine;

import java.util.Map;

/**
 * The rule-evaluation seam. A rule's {@code condition} (authored as data) is
 * tested against a request {@code context}. Today the implementation is
 * JSON-logic ({@link JsonLogicPolicyEngine}); a Drools/CEL engine could be
 * swapped behind this interface via {@code @ConditionalOnProperty} without
 * touching the store, the API, or the callers — the same pattern every other
 * vendor-neutral capability in the BSS follows.
 */
public interface PolicyEngine {

    /**
     * @return true when the rule's condition matches the context (so its
     *         effect applies). A malformed rule must return false, never throw:
     *         one bad rule can't be allowed to break the order pipeline.
     */
    boolean matches(String condition, Map<String, Object> context);
}
