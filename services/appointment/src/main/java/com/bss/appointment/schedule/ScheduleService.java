package com.bss.appointment.schedule;

import com.bss.appointment.exception.BadRequestException;
import com.bss.appointment.exception.NotFoundException;
import com.bss.appointment.security.TenantRegistry;
import com.bss.appointment.security.TenantScope;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    /** Provider keys this build ships (the ScheduleProvider beans); validated here so a typo never silently falls back. */
    public static final Set<String> KNOWN_PROVIDERS = Set.of("roster", "tmf646");

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
    public Map<String, Object> saveConfig(Map<String, Object> dto) {
        ScheduleConfig cfg = current();
        if (dto.containsKey("timezone")) {
            cfg.setTimezone(requireZone(String.valueOf(dto.get("timezone"))).getId());
        }
        if (dto.containsKey("workingDays")) {
            cfg.setWorkingDays(requireDays(dto.get("workingDays")));
        }
        if (dto.containsKey("slotStarts")) {
            cfg.setSlotStarts(requireTimes(dto.get("slotStarts")));
        }
        if (dto.containsKey("slotHours")) {
            cfg.setSlotHours(requireRange(dto.get("slotHours"), 1, 12, "slotHours"));
        }
        if (dto.containsKey("daysAhead")) {
            cfg.setDaysAhead(requireRange(dto.get("daysAhead"), 1, 90, "daysAhead"));
        }
        if (dto.containsKey("defaultCapacity")) {
            cfg.setDefaultCapacity(requireRange(dto.get("defaultCapacity"), 0, 500, "defaultCapacity"));
        }
        if (dto.containsKey("provider")) {
            String p = dto.get("provider") == null ? "roster" : String.valueOf(dto.get("provider")).trim();
            if (!KNOWN_PROVIDERS.contains(p)) {
                throw new BadRequestException("provider must be one of " + KNOWN_PROVIDERS);
            }
            cfg.setProvider(p);
        }
        if (dto.containsKey("providerUrl")) {
            String u = dto.get("providerUrl") == null ? null : String.valueOf(dto.get("providerUrl")).trim();
            if (u != null && !u.isBlank() && !(u.startsWith("http://") || u.startsWith("https://"))) {
                throw new BadRequestException("providerUrl must be an http(s) URL");
            }
            cfg.setProviderUrl(u == null || u.isBlank() ? null : u.replaceAll("/+$", ""));
        }
        if (dto.containsKey("providerSecretRef")) {
            String ref = dto.get("providerSecretRef") == null ? null : String.valueOf(dto.get("providerSecretRef")).trim();
            if (ref != null && !ref.isBlank() && !ref.matches("[A-Z][A-Z0-9_]*")) {
                throw new BadRequestException("providerSecretRef names an environment variable (UPPER_SNAKE), never the secret");
            }
            cfg.setProviderSecretRef(ref == null || ref.isBlank() ? null : ref);
        }
        if (dto.containsKey("providerCategory")) {
            String c = dto.get("providerCategory") == null ? null : String.valueOf(dto.get("providerCategory")).trim();
            cfg.setProviderCategory(c == null || c.isBlank() ? null : c);
        }
        if (!"roster".equals(cfg.getProvider()) && (cfg.getProviderUrl() == null || cfg.getProviderUrl().isBlank())) {
            throw new BadRequestException("provider '" + cfg.getProvider() + "' needs a providerUrl");
        }
        cfg.setLastUpdate(OffsetDateTime.now());
        return toMap(configs.save(cfg));
    }

    public Map<String, Object> toMap(ScheduleConfig cfg) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("tenantId", cfg.getTenantId());
        map.put("timezone", cfg.getTimezone());
        map.put("workingDays", split(cfg.getWorkingDays()));
        map.put("slotStarts", split(cfg.getSlotStarts()));
        map.put("slotHours", cfg.getSlotHours());
        map.put("daysAhead", cfg.getDaysAhead());
        map.put("defaultCapacity", cfg.getDefaultCapacity());
        map.put("rosterSize", technicians.findByTenantIdOrderByName(cfg.getTenantId()).stream()
                .filter(Technician::isActive).count());
        map.put("capacityMode", !"roster".equals(cfg.getProvider()) ? "provider"
                : technicians.findByTenantIdOrderByName(cfg.getTenantId()).isEmpty() ? "flat" : "roster");
        map.put("provider", cfg.getProvider());
        if (cfg.getProviderUrl() != null) {
            map.put("providerUrl", cfg.getProviderUrl());
        }
        if (cfg.getProviderSecretRef() != null) {
            map.put("providerSecretRef", cfg.getProviderSecretRef());
        }
        if (cfg.getProviderCategory() != null) {
            map.put("providerCategory", cfg.getProviderCategory());
        }
        map.put("lastUpdate", cfg.getLastUpdate());
        map.put("@type", "ScheduleConfig");
        return map;
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
    public List<Map<String, Object>> listTechnicians() {
        return technicians.findByTenantIdOrderByName(tenantScope.currentTenantId())
                .stream().map(this::toMap).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> technician(String id) {
        return toMap(technicians.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("Technician", id)));
    }

    @Transactional
    public Map<String, Object> createTechnician(Map<String, Object> dto) {
        if (dto.get("name") == null || String.valueOf(dto.get("name")).isBlank()) {
            throw new BadRequestException("name is required");
        }
        Technician t = new Technician();
        t.setId(UUID.randomUUID().toString());
        t.setTenantId(tenantScope.currentTenantId());
        t.setCreationDate(OffsetDateTime.now());
        apply(t, dto);
        return toMap(technicians.save(t));
    }

    @Transactional
    public Map<String, Object> patchTechnician(String id, Map<String, Object> dto) {
        Technician t = technicians.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("Technician", id));
        apply(t, dto);
        return toMap(technicians.save(t));
    }

    @Transactional
    public void deleteTechnician(String id) {
        Technician t = technicians.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("Technician", id));
        technicians.delete(t);
    }

    private void apply(Technician t, Map<String, Object> dto) {
        if (dto.containsKey("name")) {
            t.setName(String.valueOf(dto.get("name")));
        }
        if (dto.containsKey("skills")) {
            t.setSkills(dto.get("skills") == null ? null : join(dto.get("skills")));
        }
        if (dto.containsKey("zone")) {
            t.setZone(dto.get("zone") == null ? null : String.valueOf(dto.get("zone")));
        }
        if (dto.containsKey("workingDays")) {
            t.setWorkingDays(requireDays(dto.get("workingDays")));
        }
        if (dto.containsKey("startTime")) {
            t.setStartTime(requireTime(String.valueOf(dto.get("startTime"))));
        }
        if (dto.containsKey("endTime")) {
            t.setEndTime(requireTime(String.valueOf(dto.get("endTime"))));
        }
        if (LocalTime.parse(t.getStartTime()).compareTo(LocalTime.parse(t.getEndTime())) >= 0) {
            throw new BadRequestException("startTime must be before endTime");
        }
        if (dto.containsKey("active")) {
            t.setActive(Boolean.parseBoolean(String.valueOf(dto.get("active"))));
        }
        t.setLastUpdate(OffsetDateTime.now());
    }

    private Map<String, Object> toMap(Technician t) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", t.getId());
        map.put("name", t.getName());
        map.put("skills", t.getSkills() == null ? List.of() : split(t.getSkills()));
        if (t.getZone() != null) {
            map.put("zone", t.getZone());
        }
        map.put("workingDays", split(t.getWorkingDays()));
        map.put("startTime", t.getStartTime());
        map.put("endTime", t.getEndTime());
        map.put("active", t.isActive());
        map.put("creationDate", t.getCreationDate());
        map.put("lastUpdate", t.getLastUpdate());
        map.put("@type", "Technician");
        return map;
    }

    /* ---------- validation helpers ---------- */

    static String key(DayOfWeek d) {
        return d.name().substring(0, 3);
    }

    private static List<String> split(String csv) {
        return csv == null || csv.isBlank() ? List.of()
                : Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static String join(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).map(String::trim).collect(Collectors.joining(","));
        }
        return String.valueOf(value);
    }

    private static ZoneId requireZone(String id) {
        try {
            return ZoneId.of(id);
        } catch (DateTimeException e) {
            throw new BadRequestException("timezone must be an IANA zone id such as America/Guyana");
        }
    }

    private static String requireDays(Object value) {
        List<String> days = split(join(value)).stream().map(s -> s.toUpperCase(Locale.ROOT)).toList();
        if (days.isEmpty() || !DAYS.containsAll(days)) {
            throw new BadRequestException("workingDays must be a list of MON..SUN");
        }
        return String.join(",", days);
    }

    private static String requireTimes(Object value) {
        List<String> times = split(join(value)).stream().map(ScheduleService::requireTime).toList();
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

    private static int requireRange(Object value, int min, int max, String field) {
        int n;
        try {
            n = Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new BadRequestException(field + " must be a number");
        }
        if (n < min || n > max) {
            throw new BadRequestException(field + " must be between " + min + " and " + max);
        }
        return n;
    }
}
