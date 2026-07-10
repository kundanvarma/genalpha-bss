package com.bss.party.repository;

import com.bss.party.entity.BillingAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BillingAccountRepository extends JpaRepository<BillingAccount, String> {

    Optional<BillingAccount> findByIdAndTenantId(String id, String tenantId);
}
