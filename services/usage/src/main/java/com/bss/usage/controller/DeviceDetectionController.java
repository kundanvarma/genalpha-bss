package com.bss.usage.controller;

import com.bss.usage.api.ApiConstants;
import com.bss.usage.service.DeviceDetectionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The EIR / device-detection seam: a network element reports the handset a SIM is
 * in ({partyId, deviceModel, tac?, imei?}). usage:write, like the usage ingest.
 * Relayed to the CDP as a deviceModel trait — see {@link DeviceDetectionService}.
 */
@RestController
public class DeviceDetectionController {

    private final DeviceDetectionService service;

    public DeviceDetectionController(DeviceDetectionService service) {
        this.service = service;
    }

    @PostMapping(ApiConstants.BASE_PATH + "/deviceDetection")
    public ResponseEntity<Map<String, Object>> detect(@RequestBody Map<String, Object> dto) {
        return ResponseEntity.ok(service.record(dto));
    }
}
