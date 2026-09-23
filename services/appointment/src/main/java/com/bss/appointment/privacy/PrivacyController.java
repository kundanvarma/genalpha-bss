package com.bss.appointment.privacy;

import com.bss.appointment.dto.EraseReceipt;
import com.bss.appointment.dto.EraseRequest;
import com.bss.appointment.dto.PrivacyExport;
import com.bss.appointment.entity.Appointment;
import com.bss.appointment.exception.BadRequestException;
import com.bss.appointment.repository.AppointmentRepository;
import com.bss.appointment.security.TenantScope;
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

    private static final String CATEGORY = "appointments";

    private final AppointmentRepository repository;
    private final TenantScope tenantScope;

    public PrivacyController(AppointmentRepository repository, TenantScope tenantScope) {
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
        // the map read this with String.valueOf, so a body with no party looked up the literal
        // "null" and deleted nothing by luck; a real null matches owner_party_id IS NULL — every
        // guest booking — so a nameless erasure is the 400 this door always meant
        if (request == null || !request.names()) {
            throw new BadRequestException("partyId is required");
        }
        List<Appointment> rows = repository.findByTenantIdAndOwnerPartyId(
                tenantScope.currentTenantId(), request.partyId());
        repository.deleteAll(rows);
        return EraseReceipt.of(CATEGORY, rows.size());
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
