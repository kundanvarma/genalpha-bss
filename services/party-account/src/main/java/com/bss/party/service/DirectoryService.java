package com.bss.party.service;

import com.bss.party.entity.DirectoryExportRow;
import com.bss.party.entity.DirectoryExportRun;
import com.bss.party.entity.DirectorySetting;
import com.bss.party.entity.Individual;
import com.bss.party.events.DomainEventPublisher;
import com.bss.party.exception.BadRequestException;
import com.bss.party.exception.NotFoundException;
import com.bss.party.repository.DirectoryExportRowRepository;
import com.bss.party.repository.DirectoryExportRunRepository;
import com.bss.party.repository.DirectorySettingRepository;
import com.bss.party.repository.IndividualRepository;
import com.bss.party.security.PartyScope;
import com.bss.party.security.TenantScope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Directory services are an OBLIGATION, not a feature: subscriber data goes
 * to the number-directory partner as a periodic delta, and reservations are
 * honored absolutely. The model lives at party (+ optional serviceRef)
 * level: exposure full | partial | reserved, plus the mandatory free
 * secret-number service (forces reserved, suppresses everything).
 *
 * <p>Defaults are the legally shaped ones: a party derived to be a MINOR
 * (same derivation the household guardian logic uses — an adult is a known
 * birth date 18+ years back; anything else is treated as a minor, the safe
 * default) starts {@code reserved}; a known adult starts {@code full}.
 *
 * <p>The export excludes reserved, secret-number, protected-address and
 * deceased parties — THE EXCLUSION IS THE COMPLIANCE POINT; the regulator
 * audits leaks of reserved numbers. Partial exposure ships name + phone,
 * never the address.
 */
@Service
public class DirectoryService {

    private static final Set<String> EXPOSURES = Set.of(
            DirectorySetting.EXPOSURE_FULL, DirectorySetting.EXPOSURE_PARTIAL,
            DirectorySetting.EXPOSURE_RESERVED);

    private final DirectorySettingRepository settings;
    private final DirectoryExportRunRepository runs;
    private final DirectoryExportRowRepository rows;
    private final IndividualRepository individuals;
    private final DomainEventPublisher events;
    private final PartyScope partyScope;
    private final TenantScope tenantScope;
    private final ObjectMapper objectMapper;

    public DirectoryService(DirectorySettingRepository settings, DirectoryExportRunRepository runs,
            DirectoryExportRowRepository rows, IndividualRepository individuals,
            DomainEventPublisher events, PartyScope partyScope, TenantScope tenantScope,
            ObjectMapper objectMapper) {
        this.settings = settings;
        this.runs = runs;
        this.rows = rows;
        this.individuals = individuals;
        this.events = events;
        this.partyScope = partyScope;
        this.tenantScope = tenantScope;
        this.objectMapper = objectMapper;
    }

    /* ------------------------- settings (self + staff) ------------------------- */

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listSettings(String partyId) {
        requireOwn(partyId);
        requireParty(partyId);
        return settings.findByTenantIdAndPartyId(tenantScope.currentTenantId(), partyId)
                .stream().map(this::toMap).toList();
    }

    /**
     * Upsert by (party, serviceRef). Rules, in order: exposure must be one
     * of full|partial|reserved when given; secretNumber=true FORCES reserved
     * (the mandatory free service suppresses everything — no override);
     * exposure absent on first write = the legal default (minor → reserved).
     */
    @Transactional
    public Map<String, Object> upsertSetting(String partyId, String serviceRef,
            String exposure, Boolean secretNumber) {
        requireOwn(partyId);
        Individual person = requireParty(partyId);
        if (exposure != null && !EXPOSURES.contains(exposure)) {
            throw new BadRequestException("exposure is one of: full, partial, reserved");
        }
        String tenantId = tenantScope.currentTenantId();
        String normalizedRef = serviceRef == null || serviceRef.isBlank() ? null : serviceRef.trim();
        DirectorySetting setting = settings.findByTenantIdAndPartyId(tenantId, partyId).stream()
                .filter(s -> java.util.Objects.equals(s.getServiceRef(), normalizedRef))
                .findFirst().orElseGet(() -> {
                    DirectorySetting fresh = new DirectorySetting();
                    fresh.setId(UUID.randomUUID().toString());
                    fresh.setTenantId(tenantId);
                    fresh.setPartyId(partyId);
                    fresh.setServiceRef(normalizedRef);
                    fresh.setExposure(defaultExposureFor(person));
                    return fresh;
                });
        if (secretNumber != null) {
            setting.setSecretNumber(secretNumber);
        }
        if (exposure != null) {
            setting.setExposure(exposure);
        }
        if (setting.isSecretNumber()) {
            // the free service is absolute: secret number = total suppression
            setting.setExposure(DirectorySetting.EXPOSURE_RESERVED);
        }
        setting.setUpdatedAt(OffsetDateTime.now());
        return toMap(settings.save(setting));
    }

    /** Same derivation as the household guardian logic (createDependent):
     * an ADULT is a known birth date 18+ years back; everything else —
     * including an unrecorded birth date — is treated as a minor. */
    private String defaultExposureFor(Individual person) {
        boolean adult = person.getBirthDate() != null
                && person.getBirthDate().isBefore(LocalDate.now().minusYears(18));
        return adult ? DirectorySetting.EXPOSURE_FULL : DirectorySetting.EXPOSURE_RESERVED;
    }

    /* --------------------------- export (staff only) --------------------------- */

    /**
     * One delta run for the CURRENT tenant: every setting touched since the
     * previous run (all of them on the first) is re-evaluated; only
     * exportable entries produce a JSON row. Suppressed entries produce
     * NOTHING — not a tombstone; a delete-row protocol for the partner feed
     * is part of the real directory agreement (deferred, noted in the arc).
     */
    @Transactional
    public Map<String, Object> runExport() {
        requireBackOffice();
        String tenantId = tenantScope.currentTenantId();
        OffsetDateTime since = runs.findTopByTenantIdOrderByRanAtDesc(tenantId)
                .map(DirectoryExportRun::getRanAt).orElse(null);
        List<DirectorySetting> delta = since == null
                ? settings.findByTenantId(tenantId)
                : settings.findByTenantIdAndUpdatedAtAfter(tenantId, since);

        DirectoryExportRun run = new DirectoryExportRun();
        run.setId(UUID.randomUUID().toString());
        run.setTenantId(tenantId);
        run.setRanAt(OffsetDateTime.now());

        List<Map<String, Object>> exported = new ArrayList<>();
        for (DirectorySetting setting : delta) {
            Map<String, Object> payload = exportableRow(setting, tenantId);
            if (payload == null) {
                continue;
            }
            DirectoryExportRow row = new DirectoryExportRow();
            row.setId(UUID.randomUUID().toString());
            row.setTenantId(tenantId);
            row.setRunId(run.getId());
            row.setPartyId(setting.getPartyId());
            row.setServiceRef(setting.getServiceRef());
            row.setPayload(writeJson(payload));
            row.setExportedAt(run.getRanAt());
            rows.save(row);
            exported.add(payload);
        }
        run.setRowCount(exported.size());
        runs.save(run);

        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("id", run.getId());
        resource.put("ranAt", run.getRanAt().toString());
        resource.put("rowCount", run.getRowCount());
        if (since != null) {
            resource.put("deltaSince", since.toString());
        }
        events.publish("DirectoryExportedEvent", "directoryExport", resource);

        Map<String, Object> out = new LinkedHashMap<>(resource);
        out.put("rows", exported);
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getRun(String runId) {
        requireBackOffice();
        String tenantId = tenantScope.currentTenantId();
        DirectoryExportRun run = runs.findByIdAndTenantId(runId, tenantId)
                .orElseThrow(() -> NotFoundException.forResource("DirectoryExportRun", runId));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", run.getId());
        out.put("ranAt", run.getRanAt().toString());
        out.put("rowCount", run.getRowCount());
        out.put("rows", rows.findByTenantIdAndRunId(tenantId, run.getId()).stream()
                .map(r -> readJson(r.getPayload())).toList());
        return out;
    }

    /**
     * The row that ships, or null when the entry is SUPPRESSED. Suppression
     * wins over everything: reserved exposure, secret number, a protected
     * address, a deceased flag, or a party that no longer exists.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> exportableRow(DirectorySetting setting, String tenantId) {
        if (setting.isSecretNumber()
                || DirectorySetting.EXPOSURE_RESERVED.equals(setting.getExposure())) {
            return null;
        }
        Individual person = individuals
                .findByIdAndTenantId(setting.getPartyId(), tenantId).orElse(null);
        if (person == null || person.isAddressProtected() || person.isDeceased()) {
            return null;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("partyId", person.getId());
        if (setting.getServiceRef() != null) {
            payload.put("serviceRef", setting.getServiceRef());
        }
        payload.put("name", ((person.getGivenName() == null ? "" : person.getGivenName())
                + " " + person.getFamilyName()).trim());
        payload.put("exposure", setting.getExposure());
        List<Map<String, Object>> media = readMedia(person.getContactMediumJson());
        String phone = firstCharacteristic(media, "phoneNumber");
        if (phone != null) {
            payload.put("phoneNumber", phone);
        }
        if (DirectorySetting.EXPOSURE_FULL.equals(setting.getExposure())) {
            // full listing carries the postal address; partial NEVER does
            media.stream()
                    .filter(m -> m.get("characteristic") instanceof Map<?, ?> c
                            && (c.get("city") != null || c.get("street1") != null))
                    .findFirst()
                    .ifPresent(m -> payload.put("address",
                            new LinkedHashMap<>((Map<String, Object>) m.get("characteristic"))));
        }
        return payload;
    }

    /* --------------------------------- shared --------------------------------- */

    private Map<String, Object> toMap(DirectorySetting s) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", s.getId());
        out.put("partyId", s.getPartyId());
        if (s.getServiceRef() != null) {
            out.put("serviceRef", s.getServiceRef());
        }
        out.put("exposure", s.getExposure());
        out.put("secretNumber", s.isSecretNumber());
        if (s.getUpdatedAt() != null) {
            out.put("updatedAt", s.getUpdatedAt().toString());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private String firstCharacteristic(List<Map<String, Object>> media, String key) {
        for (Map<String, Object> m : media) {
            Map<String, Object> characteristic = m.get("characteristic") instanceof Map<?, ?> c
                    ? (Map<String, Object>) c : Map.of();
            if (characteristic.get(key) != null) {
                return String.valueOf(characteristic.get(key));
            }
        }
        return null;
    }

    private List<Map<String, Object>> readMedia(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {
                    });
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private String writeJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("unserializable export row", e);
        }
    }

    private Map<String, Object> readJson(String payload) {
        try {
            return objectMapper.readValue(payload,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
        } catch (JsonProcessingException e) {
            return Map.of("payload", payload);
        }
    }

    private Individual requireParty(String partyId) {
        return individuals.findByIdAndTenantId(partyId, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("Individual", partyId));
    }

    /** Customers manage their own listing; anything else reads as 404. */
    private void requireOwn(String partyId) {
        partyScope.scopedPartyId().ifPresent(own -> {
            if (!own.equals(partyId)) {
                throw NotFoundException.forResource("Individual", partyId);
            }
        });
    }

    private void requireBackOffice() {
        if (partyScope.scopedPartyId().isPresent()) {
            throw new BadRequestException("directory export is a back-office operation");
        }
    }
}
