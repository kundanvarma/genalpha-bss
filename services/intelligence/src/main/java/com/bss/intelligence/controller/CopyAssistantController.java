package com.bss.intelligence.controller;

import com.bss.intelligence.api.ApiConstants;
import com.bss.intelligence.service.CopyAssistantService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping(ApiConstants.BASE_PATH)
public class CopyAssistantController {

    private final CopyAssistantService service;

    public CopyAssistantController(CopyAssistantService service) {
        this.service = service;
    }

    @PostMapping("/campaignCopy")
    public ResponseEntity<Map<String, Object>> draft(@RequestBody Map<String, Object> request) {
        return ResponseEntity.ok(service.draftCampaignCopy(request));
    }
}
