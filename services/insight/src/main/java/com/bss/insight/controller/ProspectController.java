package com.bss.insight.controller;

import com.bss.insight.api.ApiConstants;
import com.bss.insight.dto.ProspectImport;
import com.bss.insight.dto.ProspectView;
import com.bss.insight.service.ProspectService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Prospects: capture leads and import lists (Excel paste, purchased, social
 * lead-form). Consent is stamped on import; a bought list lands unconsented. */
@RestController
@RequestMapping(ApiConstants.BASE_PATH + "/prospect")
public class ProspectController {

    private final ProspectService service;

    public ProspectController(ProspectService service) {
        this.service = service;
    }

    @PostMapping("/import")
    public ResponseEntity<ProspectImport.Receipt> importBulk(@RequestBody ProspectImport body) {
        return ResponseEntity.ok(service.importBulk(body));
    }

    @GetMapping
    public ResponseEntity<List<ProspectView>> list(
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String consent) {
        return ResponseEntity.ok(service.list(source, consent));
    }
}
