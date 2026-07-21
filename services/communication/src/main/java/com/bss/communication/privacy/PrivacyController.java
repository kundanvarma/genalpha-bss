package com.bss.communication.privacy;

import com.bss.communication.entity.CommunicationMessage;
import com.bss.communication.repository.CommunicationMessageRepository;
import com.bss.communication.security.TenantScope;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * The GDPR corner of this service. EXPORT rides the caller's OWN token —
 * a person exports what their credentials could already read; only the
 * DPO (roles:admin) may name another party. ERASE is DPO-only and
 * answers with honest counts: what this service deleted, nothing more.
 */
@RestController
@RequestMapping("/privacy/v1")
public class PrivacyController {

    private static final String CATEGORY = "messages";

    private final CommunicationMessageRepository repository;
    private final TenantScope tenantScope;

    public PrivacyController(CommunicationMessageRepository repository, TenantScope tenantScope) {
        this.repository = repository;
        this.tenantScope = tenantScope;
    }

    @GetMapping("/export")
    public Map<String, Object> export(@RequestParam(required = false) String partyId) {
        String subject = subject();
        String target = partyId == null || partyId.isBlank() ? subject : partyId;
        if (!target.equals(subject) && !isDpo()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND); // 404, never 403
        }
        List<CommunicationMessage> items = repository.findByTenantIdAndReceiverPartyId(tenantScope.currentTenantId(), target);
        return Map.of("category", CATEGORY, "count", items.size(), "items", items);
    }

    @PostMapping("/erase")
    public Map<String, Object> erase(@RequestBody Map<String, Object> request) {
        if (!isDpo()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        String target = String.valueOf(request.get("partyId"));
        List<CommunicationMessage> rows = repository.findByTenantIdAndReceiverPartyId(tenantScope.currentTenantId(), target);
        repository.deleteAll(rows);
        return Map.of("category", CATEGORY, "deleted", rows.size(), "retained", 0);
    }

    private String subject() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "" : auth.getName();
    }

    private boolean isDpo() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        for (GrantedAuthority ga : auth.getAuthorities()) {
            if ("roles:admin".equals(ga.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
