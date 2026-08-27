package com.bss.usage.repository;

import com.bss.usage.entity.AutoTopupPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AutoTopupPolicyRepository extends JpaRepository<AutoTopupPolicy, String> {

    Optional<AutoTopupPolicy> findByTenantIdAndPartyId(String tenantId, String partyId);
}
