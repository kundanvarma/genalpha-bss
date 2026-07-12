package com.bss.party.repository;

import com.bss.party.entity.PartyRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PartyRoleRepository extends JpaRepository<PartyRole, String> {

    Optional<PartyRole> findByIdAndTenantId(String id, String tenantId);

    List<PartyRole> findByTenantIdAndPartyId(String tenantId, String partyId);

    List<PartyRole> findByTenantId(String tenantId);

    boolean existsByTenantIdAndPartyIdAndName(String tenantId, String partyId, String name);
}
