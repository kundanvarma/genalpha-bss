package com.bss.document.controller;

import com.bss.document.api.ApiConstants;
import com.bss.document.entity.StoredDocument;
import com.bss.document.service.ContentResult;
import com.bss.document.service.DocumentService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(ApiConstants.BASE_PATH + "/document")
public class DocumentController {

    private final DocumentService service;

    public DocumentController(DocumentService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(dto));
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(
            @RequestParam(required = false) String category) {
        return ResponseEntity.ok(service.findAll(category));
    }

    /** Stable white-label logo URL — the host decides whose brand appears. */
    @GetMapping("/brand-logo")
    public ResponseEntity<byte[]> brandLogo() {
        StoredDocument doc = service.brandLogo();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(doc.getContentType()))
                .header("Cache-Control", "public, max-age=300")
                .body(doc.getContent());
    }

    /**
     * The anonymous shop window. A hosted document serves its bytes (cache-forever);
     * a reference document 302-redirects to the external CMS/DAM's own URL at the
     * requested {@code rendition} (thumb|card|hero|orig) — the browser hits their CDN.
     */
    @GetMapping("/{id}/content")
    public ResponseEntity<byte[]> content(@PathVariable String id,
            @RequestParam(required = false) String rendition) {
        ContentResult result = service.resolveContent(id, rendition);
        if (result.isRedirect()) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, result.redirectUrl())
                    .header("Cache-Control", "public, max-age=300")
                    .build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(result.contentType()))
                .header("Cache-Control", "public, max-age=86400")
                .body(result.bytes());
    }
}
