package com.bss.som.client;

import com.bss.som.entity.WholesaleAccessOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Dev owner OSS: every access-seeker order is accepted and activated with a fresh
 * Sonata-style reference. A real adapter would POST to the owner's LSO Sonata API
 * and complete asynchronously on its callback; the mock activates instantly, the
 * same way the dev carriers deliver instantly.
 */
@Component
@ConditionalOnProperty(name = "bss.wholesale.oss", havingValue = "mock", matchIfMissing = true)
public class MockWholesaleAccessClient implements WholesaleAccessClient {

    private static final Logger log = LoggerFactory.getLogger(MockWholesaleAccessClient.class);
    private static final String DIGITS = "0123456789";

    @Override
    public AccessOrderResult order(String accessOwner, String accessLayer, Integer bandwidthMbps,
            String postCode, String serviceId, String buyerRef) {
        SecureRandom random = new SecureRandom();
        StringBuilder ref = new StringBuilder("SO-").append(accessOwner).append('-');
        for (int i = 0; i < 6; i++) {
            ref.append(DIGITS.charAt(random.nextInt(DIGITS.length())));
        }
        log.info("mock owner OSS: {} accepted access-seeker order {} ({} {} Mbit/s at {}) for service {}",
                accessOwner, ref, accessLayer, bandwidthMbps, postCode, serviceId);
        return new AccessOrderResult(ref.toString(), WholesaleAccessOrder.ACTIVE);
    }
}
