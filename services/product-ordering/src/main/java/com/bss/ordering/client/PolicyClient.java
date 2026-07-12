package com.bss.ordering.client;

import java.util.Map;

/**
 * Read-side view of the policy component: at order time, ask whether a
 * data-authored business rule forbids this order. A definitive DENY blocks the
 * order; an unreachable policy service fails OPEN (allow) so a policy outage
 * never halts all ordering — the rule store is advisory infrastructure, not a
 * single point of failure for commerce.
 */
public interface PolicyClient {

    Decision evaluateOrder(Map<String, Object> context);

    record Decision(boolean allowed, String message, String ruleName) {
        public static Decision allow() {
            return new Decision(true, null, null);
        }

        public static Decision deny(String message, String ruleName) {
            return new Decision(false, message, ruleName);
        }
    }
}
