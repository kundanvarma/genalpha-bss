package com.bss.insight.controller;

import com.bss.insight.api.ApiConstants;
import com.bss.insight.service.SocialPublishService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Organic publishing: put a post out on the brand's handle; read the feed. */
@RestController
@RequestMapping(ApiConstants.BASE_PATH + "/social")
public class SocialPublishController {

    private final SocialPublishService service;

    public SocialPublishController(SocialPublishService service) {
        this.service = service;
    }

    @PostMapping("/publish")
    public ResponseEntity<Map<String, Object>> publish(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(service.publish(body.get("content") == null ? null : String.valueOf(body.get("content"))));
    }

    @GetMapping("/posts")
    public ResponseEntity<List<Map<String, Object>>> posts() {
        return ResponseEntity.ok(service.posts());
    }
}
