package com.bss.payment.repository;

import com.bss.payment.entity.PspRedirectSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PspRedirectSessionRepository extends JpaRepository<PspRedirectSession, String> {

    /** The session a confirm is quoting. Scoped by tenant, like every read here. */
    Optional<PspRedirectSession> findByTenantIdAndSessionRef(String tenantId, String sessionRef);
}
