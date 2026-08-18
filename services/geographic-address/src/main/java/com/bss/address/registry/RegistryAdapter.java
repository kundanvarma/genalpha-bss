package com.bss.address.registry;

import com.bss.address.entity.RegistryConfig;

import java.util.Map;

/**
 * One country's national registry, normalized (freg-address-plan F-P1). The
 * adapter normalizes the ANSWER SHAPE, never the legal basis — shipping an
 * adapter is not shipping the right to use it. Mirrors CarrierAdapter: beans
 * implementing this are auto-discovered into the {@link RegistryRegistry}.
 */
public interface RegistryAdapter {

    /** The key used in registry_config ('freg'). */
    String provider();

    /**
     * Is this person registered at the claimed address? Outcomes:
     * match / mismatch / no_data (unknown OR protected — indistinguishable by
     * design) / unavailable (registry unreachable — fail open, never block).
     */
    Result match(RegistryConfig cfg, Person person, Map<String, Object> address);

    record Person(String name, String birthDate) { }

    record Result(String outcome, Map<String, Object> registeredAddress, String movedDate) {
        public static Result of(String outcome) {
            return new Result(outcome, null, null);
        }
    }
}
