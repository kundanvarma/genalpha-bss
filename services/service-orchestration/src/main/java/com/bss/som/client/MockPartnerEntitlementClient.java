package com.bss.som.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/** Dev partner platform: every activation succeeds with a fresh code. */
@Component
@ConditionalOnProperty(name = "bss.partner.platform", havingValue = "mock", matchIfMissing = true)
public class MockPartnerEntitlementClient implements PartnerEntitlementClient {

    private static final Logger log = LoggerFactory.getLogger(MockPartnerEntitlementClient.class);
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    @Override
    public String activate(String offeringName, String customerPartyId) {
        // "NETFLIX STANDARD" -> "NS"; the rest is random — recognizably a
        // voucher, never guessable.
        String prefix = java.util.Arrays.stream(offeringName.toUpperCase().split("[^A-Z0-9]+"))
                .filter(w -> !w.isBlank()).map(w -> w.substring(0, 1))
                .reduce("", String::concat);
        SecureRandom random = new SecureRandom();
        StringBuilder code = new StringBuilder(prefix.isEmpty() ? "VAS" : prefix);
        for (int block = 0; block < 2; block++) {
            code.append('-');
            for (int i = 0; i < 4; i++) {
                code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
        }
        log.info("mock partner platform: '{}' activated for {} (code {})",
                offeringName, customerPartyId, code);
        return code.toString();
    }
}
