package com.bss.appointment.provider;

import com.bss.appointment.exception.BadRequestException;
import com.bss.appointment.schedule.ScheduleConfig;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The provider a tenant's config names — the roster unless it says otherwise. */
@Component
public class ScheduleProviders {

    private final Map<String, ScheduleProvider> byKey;

    public ScheduleProviders(List<ScheduleProvider> providers) {
        this.byKey = providers.stream().collect(Collectors.toMap(ScheduleProvider::key, Function.identity()));
    }

    public Set<String> keys() {
        return byKey.keySet();
    }

    public ScheduleProvider forConfig(ScheduleConfig cfg) {
        String key = cfg.getProvider() == null || cfg.getProvider().isBlank() ? RosterScheduleProvider.KEY : cfg.getProvider();
        ScheduleProvider p = byKey.get(key);
        if (p == null) {
            throw new BadRequestException("unknown scheduling provider '" + key + "' (known: " + byKey.keySet() + ")");
        }
        return p;
    }

    /** Where a booking made under {@code providerKey} lives, for cancel. */
    public ScheduleProvider byKey(String providerKey) {
        return byKey.getOrDefault(providerKey == null ? RosterScheduleProvider.KEY : providerKey,
                byKey.get(RosterScheduleProvider.KEY));
    }
}
