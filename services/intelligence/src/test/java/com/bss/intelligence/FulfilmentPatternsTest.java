package com.bss.intelligence;

import com.bss.intelligence.service.FulfilmentPatterns;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The copilot's fulfilment pattern is deterministic: category first, words second, missing consumed values spelled out. */
class FulfilmentPatternsTest {

    private static Map<String, Object> cfs(String id, String name, String family, List<Map<String, Object>> edges) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id); m.put("name", name); m.put("serviceType", "CFS");
        m.put("serviceSpecCharacteristic", List.of(Map.of("name", "fulfilmentFamily",
                "serviceSpecCharacteristicValue", List.of(Map.of("value", family)))));
        m.put("serviceSpecRelationship", edges);
        return m;
    }

    private static Map<String, Object> edge(String rfsId, String consumes) {
        return Map.of("id", rfsId, "relationshipType", "reliesOn", "serviceSpecRelationshipCharacteristic",
                List.of(Map.of("name", "consumes", "serviceSpecCharacteristicValue", List.of(Map.of("value", consumes)))));
    }

    private final List<Map<String, Object>> catalog = List.of(
            cfs("c-mobile", "Mobile line", "mobile", List.of(edge("r-number", "msisdn"), edge("r-ocs", "chargingSpecId,zeroRatedApps"))),
            cfs("c-bill", "Billing-only product", "billing-only", List.of()),
            cfs("c-tv", "TV entitlement", "tv", List.of()));

    @Test
    void theOfferingsCategoryDecides_andMissingChargingIsSpelledOut() {
        Map<String, Object> spec = new LinkedHashMap<>(Map.of("ref", "s1", "name", "5G Mobile Plan 50 GB", "productSpecCharacteristic", List.of()));
        Map<String, Object> offering = Map.of("ref", "o1", "specRef", "s1", "name", "5G Mobile Plan 50 GB",
                "category", List.of(Map.of("name", "Mobile plans")));
        Map<String, Object> parsed = new LinkedHashMap<>(Map.of("kind", "proposal",
                "proposal", new LinkedHashMap<>(Map.of("specs", List.of(spec), "offerings", List.of(offering)))));
        FulfilmentPatterns.attach(parsed, catalog);
        @SuppressWarnings("unchecked")
        Map<String, Object> pattern = (Map<String, Object>) spec.get("fulfilmentPattern");
        assertThat(pattern.get("cfsName")).isEqualTo("Mobile line");
        assertThat(pattern.get("family")).isEqualTo("mobile");
        assertThat(String.valueOf(pattern.get("reason"))).contains("Mobile plans");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> missing = (List<Map<String, Object>>) pattern.get("missingConsumed");
        assertThat(missing).extracting(m -> m.get("characteristic")).containsExactly("chargingSpecId", "zeroRatedApps");
        assertThat(String.valueOf(missing.get(0).get("effect"))).contains("charging will not be provisioned");
    }

    @Test
    void wordsDecideWhenThereIsNoCategory_andACarriedValueIsNotMissing() {
        Map<String, Object> spec = new LinkedHashMap<>(Map.of("ref", "s1", "name", "Travel insurance", "productSpecCharacteristic", List.of()));
        var p = FulfilmentPatterns.propose(spec, List.of(), catalog);
        assertThat(p).isPresent();
        assertThat(p.get().cfsName()).isEqualTo("Billing-only product");
        assertThat(p.get().missingConsumed()).isEmpty();

        Map<String, Object> mobile = new LinkedHashMap<>(Map.of("ref", "s2", "name", "Prepaid 10 GB",
                "productSpecCharacteristic", List.of(Map.of("name", "chargingSpecId"), Map.of("name", "zeroRatedApps"))));
        var q = FulfilmentPatterns.propose(mobile, List.of(), catalog);
        assertThat(q.get().family()).isEqualTo("mobile");
        assertThat(q.get().missingConsumed()).isEmpty();
    }

    @Test
    void noRecognisableFamilyMeansNoPattern_neverAGuess() {
        Map<String, Object> spec = new LinkedHashMap<>(Map.of("ref", "s1", "name", "Mystery", "productSpecCharacteristic", List.of()));
        assertThat(FulfilmentPatterns.propose(spec, List.of(), catalog)).isEmpty();
    }

    @Test
    void aFamilyWithTwoPatternsProposesItsPrimary() {
        // step 3 gave `mobile` a second pattern (a venue slice). Taking the first
        // match offered a 50 GB plan a slice; the primary flag settles it.
        List<Map<String, Object>> catalog = List.of(
                pattern("cfs-slice", "Priority slice", "mobile", "false"),
                pattern("cfs-line", "Mobile line", "mobile", "true"));
        Map<String, Object> spec = new java.util.HashMap<>(Map.of("name", "50 GB plan"));
        var p = FulfilmentPatterns.propose(spec, List.of(), catalog);
        assertThat(p).isPresent();
        assertThat(p.get().cfsName()).isEqualTo("Mobile line");
    }

    @Test
    void withNoPrimaryMarkedTheFullerPatternWins() {
        List<Map<String, Object>> catalog = List.of(
                pattern("cfs-slice", "Priority slice", "mobile", null),
                withRelies(pattern("cfs-line", "Mobile line", "mobile", null), 4));
        Map<String, Object> spec = new java.util.HashMap<>(Map.of("name", "50 GB plan"));
        assertThat(FulfilmentPatterns.propose(spec, List.of(), catalog).get().cfsName()).isEqualTo("Mobile line");
    }

    private static Map<String, Object> pattern(String id, String name, String family, String primary) {
        List<Map<String, Object>> chars = new java.util.ArrayList<>();
        chars.add(Map.of("name", "fulfilmentFamily", "serviceSpecCharacteristicValue", List.of(Map.of("value", family))));
        if (primary != null) {
            chars.add(Map.of("name", "primaryForFamily", "serviceSpecCharacteristicValue", List.of(Map.of("value", primary))));
        }
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("id", id); m.put("name", name); m.put("serviceType", "CFS"); m.put("serviceSpecCharacteristic", chars);
        return m;
    }

    private static Map<String, Object> withRelies(Map<String, Object> spec, int n) {
        List<Map<String, Object>> edges = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            edges.add(Map.of("relationshipType", "reliesOn", "id", "rfs-" + i, "name", "RFS " + i));
        }
        spec.put("serviceSpecRelationship", edges);
        return spec;
    }
}
