package com.bss.usage.repository;

import com.bss.usage.entity.PoolMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PoolMemberRepository extends JpaRepository<PoolMember, String> {

    List<PoolMember> findByTenantIdAndPoolId(String tenantId, String poolId);

    List<PoolMember> findByTenantIdAndPartyIdAndStatus(String tenantId, String partyId, String status);

    Optional<PoolMember> findByTenantIdAndPoolIdAndPartyId(String tenantId, String poolId, String partyId);
}
