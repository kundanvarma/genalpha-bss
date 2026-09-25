package com.bss.som.seam;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The orchestrator's map from seam name to seam adapter (CONTEXT.md: seam
 * registry). Spring hands it every {@link SeamAdapter} bean; a CFS declaring a
 * seam nobody serves in this fleet is answered with {@link Optional#empty()},
 * and the executor records that honestly rather than guessing.
 */
@Component
public class SeamRegistry {

    private final Map<String, SeamAdapter> bySeam = new LinkedHashMap<>();

    public SeamRegistry(List<SeamAdapter> adapters) {
        for (SeamAdapter a : adapters) {
            SeamAdapter previous = bySeam.put(a.seam().toLowerCase(Locale.ROOT), a);
            if (previous != null) {
                throw new IllegalStateException("two seam adapters claim seam '" + a.seam() + "': "
                        + previous.getClass().getSimpleName() + " and " + a.getClass().getSimpleName());
            }
        }
    }

    /** For a pure planner or a test: a registry over the given adapters, no Spring. */
    public static SeamRegistry of(SeamAdapter... adapters) {
        return new SeamRegistry(List.of(adapters));
    }

    public Optional<SeamAdapter> forSeam(String seam) {
        return seam == null ? Optional.empty() : Optional.ofNullable(bySeam.get(seam.toLowerCase(Locale.ROOT)));
    }

    public List<String> knownSeams() {
        return List.copyOf(bySeam.keySet());
    }
}
