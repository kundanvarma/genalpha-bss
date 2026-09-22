package com.bss.campaign.controller;

import com.bss.campaign.api.ApiConstants;
import com.bss.campaign.dto.AudienceSyncRequest;
import com.bss.campaign.dto.AudienceSyncResult;
import com.bss.campaign.service.AudienceSyncService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Push an insight segment to the tenant's social platform as a Custom
 * Audience (campaign:write — activation is back-office). */
@RestController
@RequestMapping(ApiConstants.BASE_PATH + "/audienceSync")
public class AudienceSyncController {

    private final AudienceSyncService service;

    public AudienceSyncController(AudienceSyncService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<AudienceSyncResult> sync(@RequestBody AudienceSyncRequest dto) {
        return ResponseEntity.ok(service.sync(dto));
    }
}
