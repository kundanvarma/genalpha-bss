package com.bss.policy.engine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule engine is the load-bearing piece — rules are data, so their
 * evaluation must be exactly right. These cover the shapes an operator will
 * actually author from the console.
 */
class JsonLogicTest {

    @Test
    void quantityCap_deniesWhenOverLimit() {
        Object rule = Map.of(">", List.of(Map.of("var", "maxLineQuantity"), 5));
        assertTrue(JsonLogic.test(rule, Map.of("maxLineQuantity", 6)));
        assertFalse(JsonLogic.test(rule, Map.of("maxLineQuantity", 5)));
        assertFalse(JsonLogic.test(rule, Map.of("maxLineQuantity", 2)));
    }

    @Test
    void quantityCap_missingVarNeverMatches() {
        // A missing quantity must never read as "over the cap".
        Object rule = Map.of(">", List.of(Map.of("var", "maxLineQuantity"), 5));
        assertFalse(JsonLogic.test(rule, Map.of()));
    }

    @Test
    void perOfferingQuantityCap_usesNestedVar() {
        Object rule = Map.of(">", List.of(Map.of("var", "quantityByOffering.sim-only"), 2));
        assertTrue(JsonLogic.test(rule, Map.of("quantityByOffering", Map.of("sim-only", 3))));
        assertFalse(JsonLogic.test(rule, Map.of("quantityByOffering", Map.of("sim-only", 1))));
        assertFalse(JsonLogic.test(rule, Map.of("quantityByOffering", Map.of("phone", 9))));
    }

    @Test
    void incompatibility_deniesWhenBothOfferingsPresent() {
        Object rule = Map.of("and", List.of(
                Map.of("in", List.of("offer-a", Map.of("var", "offeringIds"))),
                Map.of("in", List.of("offer-b", Map.of("var", "offeringIds")))));
        assertTrue(JsonLogic.test(rule, Map.of("offeringIds", List.of("offer-a", "offer-b", "x"))));
        assertFalse(JsonLogic.test(rule, Map.of("offeringIds", List.of("offer-a", "x"))));
        assertFalse(JsonLogic.test(rule, Map.of("offeringIds", List.of())));
    }

    @Test
    void eligibility_requiresVerifiedIdentityFlag() {
        // "deny if ordering a regulated offering without a verified identity"
        Object rule = Map.of("and", List.of(
                Map.of("in", List.of("regulated-plan", Map.of("var", "offeringIds"))),
                Map.of("!", Map.of("var", "verifiedIdentity"))));
        assertTrue(JsonLogic.test(rule, Map.of(
                "offeringIds", List.of("regulated-plan"), "verifiedIdentity", false)));
        assertFalse(JsonLogic.test(rule, Map.of(
                "offeringIds", List.of("regulated-plan"), "verifiedIdentity", true)));
    }

    @Test
    void logicalAndArithmeticBasics() {
        assertTrue(JsonLogic.test(Map.of("==", List.of(2, 2)), Map.of()));
        assertEquals(5.0, JsonLogic.apply(Map.of("+", List.of(2, 3)), Map.of()));
        assertTrue(JsonLogic.test(Map.of("or", List.of(false, true)), Map.of()));
        assertFalse(JsonLogic.test(Map.of("and", List.of(true, false)), Map.of()));
    }

    @Test
    void unknownOperatorIsInertNotFatal() {
        // A rule the engine doesn't understand must be falsy, never throw.
        assertFalse(JsonLogic.test(Map.of("frobnicate", List.of(1, 2)), Map.of()));
    }

    @Test
    void pricingCondition_appliesOnlyWhenContextMatches() {
        // "discount when the cart contains a specific offering"
        Object cond = Map.of("in", List.of("fiber-1000", Map.of("var", "offeringIds")));
        assertTrue(JsonLogic.test(cond, Map.of("offeringIds", List.of("fiber-1000", "tv"), "subtotal", 80)));
        assertFalse(JsonLogic.test(cond, Map.of("offeringIds", List.of("tv"), "subtotal", 80)));
    }

    @Test
    void pricingCondition_segmentByCharacteristic() {
        // "discount for business segment customers"
        Object cond = Map.of("==", List.of(Map.of("var", "segment"), "business"));
        assertTrue(JsonLogic.test(cond, Map.of("segment", "business")));
        assertFalse(JsonLogic.test(cond, Map.of("segment", "consumer")));
    }
}
