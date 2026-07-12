package com.bss.ordering.client;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Policy evaluation switched off (tests, minimal deployments): everything is allowed. */
@Component
@ConditionalOnProperty(name = "bss.policy.enabled", havingValue = "false")
public class NoopPolicyClient implements PolicyClient {

    @Override
    public Decision evaluateOrder(Map<String, Object> context) {
        return Decision.allow();
    }
}
