package com.bss.party.controller;

import com.bss.party.api.ApiConstants;
import com.bss.party.dto.IndividualDto;
import com.bss.party.dto.RegistryLinkRequest;
import com.bss.party.dto.RegistrySyncReceipt;
import com.bss.party.service.RegistrySyncService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


/**
 * Registry re-sync plumbing (back-office): link a party to its registry
 * person id, and the manual feed-poll trigger the e2e suites and consoles
 * use (the scheduled worker does the same thing on a clock).
 */
@RestController
@RequestMapping(ApiConstants.PARTY_BASE)
public class RegistrySyncController {

    private final RegistrySyncService service;

    public RegistrySyncController(RegistrySyncService service) {
        this.service = service;
    }

    @PostMapping("/individual/{id}/registryLink")
    public ResponseEntity<IndividualDto> link(@PathVariable("id") String id,
            @RequestBody RegistryLinkRequest body) {
        return ResponseEntity.ok(service.linkRegistryPerson(id, body.personRef()));
    }

    @PostMapping("/registrySync/run")
    public ResponseEntity<RegistrySyncReceipt> run() {
        return ResponseEntity.ok(service.runOnce());
    }
}
