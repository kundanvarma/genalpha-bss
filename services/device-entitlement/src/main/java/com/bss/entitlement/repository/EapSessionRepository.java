package com.bss.entitlement.repository;

import com.bss.entitlement.entity.EapSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface EapSessionRepository extends JpaRepository<EapSession, String> {

    Optional<EapSession> findByIdAndTenantId(String id, String tenantId);

    long deleteByCreatedAtBefore(OffsetDateTime cutoff);
}
