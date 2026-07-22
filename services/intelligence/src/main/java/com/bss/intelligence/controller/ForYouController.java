package com.bss.intelligence.controller;

import com.bss.intelligence.api.ApiConstants;
import com.bss.intelligence.service.ForYouService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The customer's own rail. SELF-scoped by construction: the party is the
 * token's subject — there is no partyId parameter to probe, so this
 * endpoint can never show anyone a shop that is not their own. (The CSR
 * desk's view of a customer stays on the existing NBO under ai:use.)
 */
@RestController
@RequestMapping(ApiConstants.BASE_PATH)
public class ForYouController {

    private final ForYouService forYou;

    public ForYouController(ForYouService forYou) {
        this.forYou = forYou;
    }

    @GetMapping("/forYou")
    public ResponseEntity<Map<String, Object>> forYou() {
        String subject = SecurityContextHolder.getContext().getAuthentication().getName();
        return ResponseEntity.ok(forYou.forParty(subject));
    }
}
