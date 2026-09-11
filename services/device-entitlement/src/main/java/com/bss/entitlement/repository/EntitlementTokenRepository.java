package com.bss.entitlement.repository;

import com.bss.entitlement.entity.EntitlementToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface EntitlementTokenRepository extends JpaRepository<EntitlementToken, String> {

    Optional<EntitlementToken> findByTokenAndTenantId(String token, String tenantId);

    List<EntitlementToken> findByTenantIdAndImsi(String tenantId, String imsi);

    long deleteByExpiresAtBefore(OffsetDateTime cutoff);
}
