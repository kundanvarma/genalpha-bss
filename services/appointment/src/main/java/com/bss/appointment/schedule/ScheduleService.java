package com.bss.appointment.schedule;

import com.bss.appointment.dto.Json;
import com.bss.appointment.dto.ScheduleConfigRequest;
import com.bss.appointment.dto.ScheduleConfigView;
import com.bss.appointment.dto.TechnicianRequest;
import com.bss.appointment.dto.TechnicianView;
import com.bss.appointment.exception.BadRequestException;
import com.bss.appointment.exception.NotFoundException;
import com.bss.appointment.security.TenantRegistry;
import com.bss.appointment.security.TenantScope;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The tenant's installation calendar. Two sources decide what a customer is
 * offered: a per-tenant schedule (timezone, working days, windows, how far
 * ahead) and a technician roster. Capacity is DERIVED — a window holds as
 * many visits as there are active technicians whose working hours cover it.
 * A tenant that has not entered a roster keeps a flat default capacity, so
 * nothing changes for operators who never open this.
 */
@Service
public class ScheduleService {

    /**
     * Provider keys this build ships (the ScheduleProvider beans); validated here so a typo never
     * silently falls back. The refusal message prints this table, so the order is pinned to the one
     * the wire already has — a {@code Set.of} re-salts itself on every JVM start.
     */
    public static final Set<String> KNOWN_PROVIDERS =
            Collections.unmodifiableSet(new LinkedHashSet<>(List.of("tmf646", "roster")));

    private static final Set<String> DAYS = Arrays.stream(DayOfWeek.values())
            .map(ScheduleService::key).collect(Collectors.toSet());

    private final ScheduleConfigRepository configs;
    private final TechnicianRepository technicians;
    private final TenantScope tenantScope;
    private final TenantRegistry registry;

    public ScheduleService(ScheduleConfigRepository configs, TechnicianRepository technicians,
            TenantScope tenantScope, TenantRegistry registry) {
        this.configs = configs;
        this.technicians = technicians;
        this.tenantScope = tenantScope;
        this.registry = registry;
    }

    /* ---------- schedule config ---------- */

    /** The tenant's schedule, or the built-in defaults when none was saved (timezone from the tenant registry). */
    @Transactional(readOnly = true)
    public ScheduleConfig current() {
        String tenantId = tenantScope.currentTenantId();
        return configs.findById(tenantId).orElseGet(() -> defaults(tenantId));
    }

    private ScheduleConfig defaults(String tenantId) {
        ScheduleConfig cfg = new ScheduleConfig();
        cfg.setTenantId(tenantId);
        TenantRegistry.TenantEntry tenant = registry.byId(tenantId);
        if (tenant != null && tenant.getTimezone() != null && !tenant.getTimezone().isBlank()) {
            cfg.setTimezone(tenant.getTimezone());
        }
        return cfg;
    }

    @Transactional
    public ScheduleConfigView saveConfig(ScheduleConfigRequest dto) {
        ScheduleConfig cfg = current();
        if (Json.present(dto.timezone())) {
            cfg.setTimezone(requireZone(Json.valueOf(dto.timezone())).getId());
        }
        if (Json.present(dto.workingDays())) {
            cfg.setWorkingDays(requireDays(dto.workingDays()));
        }
        if (Json.present(dto.slotStarts())) {
            cfg.setSlotStarts(requireTimes(dto.slotStarts()));
        }
        if (Json.present(dto.slotHours())) {
            cfg.setSlotHours(requireRange(dto.slotHours(), 1, 12, "slotHours"));
        }
        if (Json.present(dto.daysAhead())) {
            cfg.setDaysAhead(requireRange(dto.daysAhead(), 1, 90, "daysAhead"));
        }
        if (Json.present(dto.defaultCapacity())) {
            cfg.setDefaultCapacity(requireRange(dto.defaultCapacity(), 0, 500, "defaultCapacity"));
        }
        if (Json.present(dto.provider())) {
            // an explicit null asked for the roster back: the map's get answered null for both
            String p = Json.textOrNull(dto.provider()) == null ? "roster" : Json.valueOf(dto.provider()).trim();
            if (!KNOWN_PROVIDERS.contains(p)) {
                throw new BadRequestException("provider must be one of " + KNOWN_PROVIDERS);
            }
            cfg.setProvider(p);
        }
        if (Json.present(dto.providerUrl())) {
            String u = trimmedOrNull(dto.providerUrl());
            if (u != null && !u.isBlank() && !(u.startsWith("http://") || u.startsWith("https://"))) {
                throw new BadRequestException("providerUrl must be an http(s) URL");
            }
            cfg.setProviderUrl(u == null || u.isBlank() ? null : u.replaceAll("/+$", ""));
        }
        if (Json.present(dto.providerSecretRef())) {
            String ref = trimmedOrNull(dto.providerSecretRef());
            if (ref != null && !ref.isBlank() && !ref.matches("[A-Z][A-Z0-9_]*")) {
                throw new BadRequestException("providerSecretRef names an environment variable (UPPER_SNAKE), never the secret");
            }
            cfg.setProviderSecretRef(ref == null || ref.isBlank() ? null : ref);
        }
        if (Json.present(dto.providerCategory())) {
            String c = trimmedOrNull(dto.providerCategory());
            cfg.setProviderCategory(c == null || c.isBlank() ? null : c);
        }
        if (!"roster".equals(cfg.getProvider()) && (cfg.getProviderUrl() == null || cfg.getProviderUrl().isBlank())) {
            throw new BadRequestException("provider '" + cfg.getProvider() + "' needs a providerUrl");
        }
        cfg.setLastUpdate(OffsetDateTime.now());
        return toView(configs.save(cfg));
    }

    public ScheduleConfigView toView(ScheduleConfig cfg) {
        List<Technician> roster = technicians.findByTenantIdOrderByName(cfg.getTenantId());
        return new ScheduleConfigView(
                cfg.getTenantId(),
                cfg.getTimezone(),
                split(cfg.getWorkingDays()),
                split(cfg.getSlotStarts()),
                cfg.getSlotHours(),
                cfg.getDaysAhead(),
                cfg.getDefaultCapacity(),
                roster.stream().filter(Technician::isActive).count(),
                !"roster".equals(cfg.getProvider()) ? "provider" : roster.isEmpty() ? "flat" : "roster",
                cfg.getProvider(),
                cfg.getProviderUrl(),
                cfg.getProviderSecretRef(),
                cfg.getProviderCategory(),
                cfg.getLastUpdate());
    }

    private static String trimmedOrNull(JsonNode node) {
        String v = Json.textOrNull(node);
        return v == null ? null : v.trim();
    }

    /* ---------- capacity ---------- */

    /**
     * How many visits the window starting at {@code start} can hold. With a
     * roster: active technicians working that weekday (in the tenant's zone)
     * whose hours cover the whole window. Without one: the flat default.
     */
    @Transactional(readOnly = true)
    public int capacityAt(OffsetDateTime start, OffsetDateTime end) {
        ScheduleConfig cfg = current();
        List<Technician> roster = technicians.findByTenantIdOrderByName(cfg.getTenantId());
        if (roster.isEmpty()) {
            return cfg.getDefaultCapacity();
        }
        ZoneId zone = ZoneId.of(cfg.getTimezone());
        ZonedDateTime from = start.atZoneSameInstant(zone);
        ZonedDateTime to = end.atZoneSameInstant(zone);
        String day = key(from.getDayOfWeek());
        LocalTime open = from.toLocalTime();
        LocalTime close = to.toLocalDate().isAfter(from.toLocalDate()) ? LocalTime.MAX : to.toLocalTime();
        int capacity = 0;
        for (Technician t : roster) {
            if (!t.isActive() || !split(t.getWorkingDays()).contains(day)) {
                continue;
            }
            LocalTime s = LocalTime.parse(t.getStartTime());
            LocalTime e = LocalTime.parse(t.getEndTime());
            if (!s.isAfter(open) && !e.isBefore(close)) {
                capacity++;
            }
        }
        return capacity;
    }

    /** Every candidate window over the configured horizon, in the tenant's zone: tomorrow onward, working days only. */
    @Transactional(readOnly = true)
    public List<OffsetDateTime[]> windows() {
        ScheduleConfig cfg = current();
        ZoneId zone = ZoneId.of(cfg.getTimezone());
        Set<String> days = Set.copyOf(split(cfg.getWorkingDays()));
        List<LocalTime> starts = split(cfg.getSlotStarts()).stream().map(LocalTime::parse).toList();
        List<OffsetDateTime[]> out = new ArrayList<>();
        java.time.LocalDate day = java.time.LocalDate.now(zone).plusDays(1);
        for (int d = 0; d < cfg.getDaysAhead(); d++, day = day.plusDays(1)) {
            if (!days.contains(key(day.getDayOfWeek()))) {
                continue;
            }
            for (LocalTime t : starts) {
                OffsetDateTime start = day.atTime(t).atZone(zone).toOffsetDateTime();
                out.add(new OffsetDateTime[] {start, start.plusHours(cfg.getSlotHours())});
            }
        }
        return out;
    }

    /** A stored instant rendered in the tenant's calendar zone (so "09:00" reads as 09:00 in Georgetown, not UTC). */
    @Transactional(readOnly = true)
    public OffsetDateTime inTenantZone(OffsetDateTime instant) {
        return instant.atZoneSameInstant(ZoneId.of(current().getTimezone())).toOffsetDateTime();
    }

    /* ---------- technicians ---------- */

    @Transactional(readOnly = true)
    public List<TechnicianView> listTechnicians() {
        return technicians.findByTenantIdOrderByName(tenantScope.currentTenantId())
                .stream().map(ScheduleService::toView).toList();
    }

    @Transactional(readOnly = true)
    public TechnicianView technician(String id) {
        return toView(technicians.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("Technician", id)));
    }

    @Transactional
    public TechnicianView createTechnician(TechnicianRequest body) {
        TechnicianRequest dto = body == null ? TechnicianRequest.EMPTY : body;
        if (!dto.named()) {
            throw new BadRequestException("name is required");
        }
        Technician t = new Technician();
        t.setId(UUID.randomUUID().toString());
        t.setTenantId(tenantScope.currentTenantId());
        t.setCreationDate(OffsetDateTime.now());
        apply(t, dto);
        return toView(technicians.save(t));
    }

    @Transactional
    public TechnicianView patchTechnician(String id, TechnicianRequest body) {
        Technician t = technicians.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("Technician", id));
        apply(t, body == null ? TechnicianRequest.EMPTY : body);
        return toView(technicians.save(t));
    }

    @Transactional
    public void deleteTechnician(String id) {
        Technician t = technicians.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("Technician", id));
        technicians.delete(t);
    }

    private void apply(Technician t, TechnicianRequest dto) {
        if (Json.present(dto.name())) {
            t.setName(Json.valueOf(dto.name()));
        }
        if (Json.present(dto.skills())) {
            t.setSkills(Json.textOrNull(dto.skills()) == null ? null : Json.join(dto.skills()));
        }
        if (Json.present(dto.zone())) {
            t.setZone(Json.textOrNull(dto.zone()));
        }
        if (Json.present(dto.workingDays())) {
            t.setWorkingDays(requireDays(dto.workingDays()));
        }
        if (Json.present(dto.startTime())) {
            t.setStartTime(requireTime(Json.valueOf(dto.startTime())));
        }
        if (Json.present(dto.endTime())) {
            t.setEndTime(requireTime(Json.valueOf(dto.endTime())));
        }
        if (LocalTime.parse(t.getStartTime()).compareTo(LocalTime.parse(t.getEndTime())) >= 0) {
            throw new BadRequestException("startTime must be before endTime");
        }
        if (Json.present(dto.active())) {
            t.setActive(Boolean.parseBoolean(Json.valueOf(dto.active())));
        }
        t.setLastUpdate(OffsetDateTime.now());
    }

    private static TechnicianView toView(Technician t) {
        return new TechnicianView(
                t.getId(),
                t.getName(),
                t.getSkills() == null ? List.of() : split(t.getSkills()),
                t.getZone(),
                split(t.getWorkingDays()),
                t.getStartTime(),
                t.getEndTime(),
                t.isActive(),
                t.getCreationDate(),
                t.getLastUpdate());
    }

    /* ---------- validation helpers ---------- */

    static String key(DayOfWeek d) {
        return d.name().substring(0, 3);
    }

    private static List<String> split(String csv) {
        return csv == null || csv.isBlank() ? List.of()
                : Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static ZoneId requireZone(String id) {
        try {
            return ZoneId.of(id);
        } catch (DateTimeException e) {
            throw new BadRequestException("timezone must be an IANA zone id such as America/Guyana");
        }
    }

    private static String requireDays(JsonNode value) {
        List<String> days = split(Json.join(value)).stream().map(s -> s.toUpperCase(Locale.ROOT)).toList();
        if (days.isEmpty() || !DAYS.containsAll(days)) {
            throw new BadRequestException("workingDays must be a list of MON..SUN");
        }
        return String.join(",", days);
    }

    private static String requireTimes(JsonNode value) {
        List<String> times = split(Json.join(value)).stream().map(ScheduleService::requireTime).toList();
        if (times.isEmpty()) {
            throw new BadRequestException("slotStarts needs at least one HH:mm");
        }
        return String.join(",", times);
    }

    private static String requireTime(String value) {
        try {
            return LocalTime.parse(value).toString();
        } catch (DateTimeParseException e) {
            throw new BadRequestException("'" + value + "' is not an HH:mm time");
        }
    }

    private static int requireRange(JsonNode value, int min, int max, String field) {
        int n;
        try {
            n = Integer.parseInt(Json.valueOf(value));
        } catch (NumberFormatException e) {
            throw new BadRequestException(field + " must be a number");
        }
        if (n < min || n > max) {
            throw new BadRequestException(field + " must be between " + min + " and " + max);
        }
        return n;
    }
}
