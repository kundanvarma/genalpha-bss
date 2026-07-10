package com.bss.party.repository;

import com.bss.party.entity.SettlementAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SettlementAccountRepository extends JpaRepository<SettlementAccount, String> {

    Optional<SettlementAccount> findByIdAndTenantId(String id, String tenantId);
}
