package com.bss.usage.service;

import com.bss.usage.events.DomainEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Device detection — the NETWORK's truth about which handset a SIM is in, not the
 * commercial "what they bought". The EIR / device-detection (IMEI -> TAC -> model)
 * pushes here; we relay it to the bus as a DeviceDetectedEvent, and the CDP turns
 * it into a single-valued {@code deviceModel} trait. A device swap replaces it, so
 * "customers on an iPhone 15" reflects the live network, and audiences re-home
 * automatically — including BYOD subscribers who never bought a handset from us.
 */
@Service
public class DeviceDetectionService {

    private final DomainEventPublisher events;

    public DeviceDetectionService(DomainEventPublisher events) {
        this.events = events;
    }

    public Map<String, Object> record(Map<String, Object> dto) {
        String partyId = str(dto.get("partyId"));
        String model = str(dto.get("deviceModel"));
        if (partyId == null || partyId.isBlank() || model == null || model.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "partyId and deviceModel are required (the EIR resolves IMEI/TAC to a model)");
        }
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("partyId", partyId);
        resource.put("deviceModel", model);
        if (dto.get("tac") != null) {
            resource.put("tac", str(dto.get("tac")));
        }
        if (dto.get("imei") != null) {
            resource.put("imei", str(dto.get("imei")));
        }
        events.publish("DeviceDetectedEvent", "deviceDetection", resource);
        return Map.of("status", "recorded", "partyId", partyId, "deviceModel", model);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
