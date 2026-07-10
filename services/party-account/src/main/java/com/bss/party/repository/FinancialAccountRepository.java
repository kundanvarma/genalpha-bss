package com.bss.party.repository;

import com.bss.party.entity.FinancialAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface FinancialAccountRepository extends JpaRepository<FinancialAccount, String> {

    Optional<FinancialAccount> findByIdAndTenantId(String id, String tenantId);
}
