package com.bss.insight.repository;

import com.bss.insight.entity.TwinVault;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TwinVaultRepository extends JpaRepository<TwinVault, String> {

    Optional<TwinVault> findByTenantIdAndSignalId(String tenantId, String signalId);

    long deleteByTenantIdAndPartyId(String tenantId, String partyId);
}
