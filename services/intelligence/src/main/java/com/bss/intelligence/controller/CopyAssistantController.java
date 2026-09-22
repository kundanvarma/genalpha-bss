package com.bss.intelligence.controller;

import com.bss.intelligence.api.ApiConstants;
import com.bss.intelligence.service.CampaignCopy;
import com.bss.intelligence.service.CopilotRequests.CopyBrief;
import com.bss.intelligence.service.CopyAssistantService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiConstants.BASE_PATH)
public class CopyAssistantController {

    private final CopyAssistantService service;

    public CopyAssistantController(CopyAssistantService service) {
        this.service = service;
    }

    @PostMapping("/campaignCopy")
    public ResponseEntity<CampaignCopy> draft(@RequestBody CopyBrief request) {
        return ResponseEntity.ok(service.draftCampaignCopy(request));
    }
}
