package com.bss.som.seam.adapters;

import com.bss.som.seam.SeamAdapter;
import com.bss.som.seam.SeamContext;
import com.bss.som.seam.SeamResult;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/**
 * Seam {@code cpe}: the router or set-top box managed over the ACS. Registered
 * so the catalog may declare it, ENVIRONMENT-GATED and never applying at order
 * time: the equipment is managed once it is installed and calls home, not when
 * the order is placed.
 */
@Component
public class CpeSeam implements SeamAdapter {

    @Override
    public String seam() {
        return "cpe";
    }

    @Override
    public boolean environmentGated() {
        return true;
    }

    @Override
    public boolean appliesTo(SeamContext ctx) {
        return false; // equipment is managed after install, never provisioned at order time
    }

    @Override
    public Set<String> consumes() {
        return Set.of();
    }

    @Override
    public Optional<String> vendor(String tenantId) {
        return Optional.of("acs");
    }

    @Override
    public SeamResult run(SeamContext ctx) {
        return SeamResult.skipped("equipment is managed after install, not at order time");
    }
}
