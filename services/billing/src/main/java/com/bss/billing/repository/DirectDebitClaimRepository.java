package com.bss.billing.repository;

import com.bss.billing.entity.DirectDebitClaim;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DirectDebitClaimRepository extends JpaRepository<DirectDebitClaim, String> {

    Optional<DirectDebitClaim> findByTenantIdAndBillId(String tenantId, String billId);

    List<DirectDebitClaim> findByTenantIdAndKid(String tenantId, String kid);

    List<DirectDebitClaim> findTop100ByTenantIdOrderByRequestedAtDesc(String tenantId);
}
