package com.bss.intelligence.controller;

import com.bss.intelligence.api.ApiConstants;
import com.bss.intelligence.security.TenantRegistry;
import com.bss.intelligence.security.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bring-your-own speech-to-text (the copilot mic's server half). The tenant's
 * Whisper-shaped provider is a per-tenant seam (speech-url/speech-token in the
 * registry, same pattern as the market feed); the mic posts audio here, the
 * seam returns a transcript, and the HUMAN still reads it and presses Send —
 * voice changes the keyboard, never the approval. No binding = the console
 * falls back to browser Web Speech, exactly as before.
 */
@RestController
@RequestMapping(ApiConstants.BASE_PATH)
public class SpeechController {

    private static final Logger log = LoggerFactory.getLogger(SpeechController.class);

    private final TenantRegistry tenants;
    private final TenantScope tenantScope;
    private final RestClient.Builder builder;

    public SpeechController(TenantRegistry tenants, TenantScope tenantScope, RestClient.Builder builder) {
        this.tenants = tenants;
        this.tenantScope = tenantScope;
        this.builder = builder;
    }

    /** Does this tenant have a server STT bound? The console mic probes this
     * once and only records audio when the answer is yes. */
    @GetMapping("/transcribe/available")
    public ResponseEntity<Map<String, Object>> available() {
        TenantRegistry.TenantEntry t = tenants.byId(tenantScope.currentTenantId());
        boolean bound = t != null && t.getSpeechUrl() != null && !t.getSpeechUrl().isBlank();
        return ResponseEntity.ok(Map.of("available", bound));
    }

    /** Proxy the audio to the tenant's Whisper-shaped provider
     * (POST {speech-url}/v1/audio/transcriptions, multipart) → { text }. */
    @PostMapping(value = "/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> transcribe(@RequestParam("audio") MultipartFile audio) {
        TenantRegistry.TenantEntry t = tenants.byId(tenantScope.currentTenantId());
        if (t == null || t.getSpeechUrl() == null || t.getSpeechUrl().isBlank()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "no speech-to-text provider bound for this tenant"));
        }
        try {
            MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
            byte[] bytes = audio.getBytes();
            String filename = audio.getOriginalFilename() == null ? "audio.webm" : audio.getOriginalFilename();
            form.add("file", new ByteArrayResource(bytes) {
                @Override
                public String getFilename() {
                    return filename;
                }
            });
            form.add("model", "whisper-1");
            RestClient.RequestBodySpec req = builder
                    .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory())
                    .build().post()
                    .uri(t.getSpeechUrl() + "/v1/audio/transcriptions")
                    .contentType(MediaType.MULTIPART_FORM_DATA);
            if (t.getSpeechToken() != null && !t.getSpeechToken().isBlank()) {
                req = req.header("Authorization", "Bearer " + t.getSpeechToken());
            }
            Map<String, Object> resp = req.body(form).retrieve().body(Map.class);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("text", resp == null ? "" : String.valueOf(resp.getOrDefault("text", "")));
            out.put("provider", "speech seam (" + t.getSpeechUrl() + ")");
            log.info("transcribed {} byte(s) of audio via the speech seam", bytes.length);
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            log.warn("speech seam failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "speech provider unreachable"));
        }
    }
}
