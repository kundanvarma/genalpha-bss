package com.bss.som.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** In-process partner platform ({@code mock}): every activation is active at once with a fresh code. */
@Component
public class MockPartnerEntitlementClient implements PartnerPlatform {

    private static final Logger log = LoggerFactory.getLogger(MockPartnerEntitlementClient.class);
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private final SecureRandom random = new SecureRandom();
    /** the request key is the idempotency key here too: the same key is the same code */
    private final Map<String, String> codes = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public String vendor(String tenantId) {
        return "mock-partner-platform";
    }

    @Override
    public Activation activate(String tenantId, String requestKey, String offeringName, String customerPartyId) {
        String code = codes.computeIfAbsent(requestKey, k -> mint(offeringName));
        log.info("mock partner platform: '{}' activated for {} (code {})", offeringName, customerPartyId, code);
        return Activation.active(code);
    }

    @Override
    public Activation status(String tenantId, String requestKey) {
        String code = codes.get(requestKey);
        return code == null ? Activation.unknown("never received") : Activation.active(code);
    }

    @Override
    public void deactivate(String tenantId, String requestKey, String activationCode, String customerPartyId) {
        codes.remove(requestKey);
        log.info("mock partner platform: code {} deactivated for {}", activationCode, customerPartyId);
    }

    /** "NETFLIX STANDARD" -> "NS-XXXX-XXXX": recognisably a voucher, never guessable. */
    private String mint(String offeringName) {
        String prefix = java.util.Arrays.stream(String.valueOf(offeringName).toUpperCase().split("[^A-Z0-9]+"))
                .filter(w -> !w.isBlank()).map(w -> w.substring(0, 1)).reduce("", String::concat);
        StringBuilder code = new StringBuilder(prefix.isEmpty() ? "VAS" : prefix);
        for (int block = 0; block < 2; block++) {
            code.append('-');
            for (int i = 0; i < 4; i++) {
                code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
        }
        return code.toString();
    }
}
