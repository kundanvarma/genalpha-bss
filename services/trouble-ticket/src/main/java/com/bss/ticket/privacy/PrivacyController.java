package com.bss.ticket.privacy;

import com.bss.ticket.entity.TroubleTicket;
import com.bss.ticket.exception.BadRequestException;
import com.bss.ticket.repository.TroubleTicketRepository;
import com.bss.ticket.security.TenantScope;
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

/**
 * The GDPR corner of this service. EXPORT rides the caller's OWN token —
 * a person exports what their credentials could already read; only the
 * DPO (roles:admin) may name another party. ERASE is DPO-only and
 * answers with honest counts: what this service deleted, nothing more.
 */
@RestController
@RequestMapping("/privacy/v1")
public class PrivacyController {

    private static final String CATEGORY = "tickets";

    private final TroubleTicketRepository repository;
    private final TenantScope tenantScope;

    public PrivacyController(TroubleTicketRepository repository, TenantScope tenantScope) {
        this.repository = repository;
        this.tenantScope = tenantScope;
    }

    @GetMapping("/export")
    public PrivacyExport export(@RequestParam(required = false) String partyId) {
        String subject = subject();
        String target = partyId == null || partyId.isBlank() ? subject : partyId;
        if (!target.equals(subject) && !isDpo()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND); // 404, never 403
        }
        return PrivacyExport.of(CATEGORY,
                repository.findByTenantIdAndOwnerPartyId(tenantScope.currentTenantId(), target));
    }

    @PostMapping("/erase")
    public EraseReceipt erase(@RequestBody EraseRequest request) {
        if (!isDpo()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (request.partyId() == null) {
            // A nameless erasure is the 400 this door always meant. The map
            // path looked up the literal "null" and deleted nothing by luck;
            // an honest null here would render as owner_party_id IS NULL and
            // take every ownerless ticket (social care opens 149 of them).
            throw new BadRequestException("partyId is required — an erasure names the person");
        }
        List<TroubleTicket> rows = repository.findByTenantIdAndOwnerPartyId(
                tenantScope.currentTenantId(), request.partyId());
        repository.deleteAll(rows);
        return new EraseReceipt(CATEGORY, rows.size(), 0);
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
