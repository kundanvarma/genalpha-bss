package com.bss.billing.repository;

import com.bss.billing.entity.DirectDebitMandate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DirectDebitMandateRepository extends JpaRepository<DirectDebitMandate, String> {

    Optional<DirectDebitMandate> findByTenantIdAndPartyId(String tenantId, String partyId);

    List<DirectDebitMandate> findByTenantIdAndStatus(String tenantId, String status);

    List<DirectDebitMandate> findTop100ByTenantIdOrderByRegisteredAtDesc(String tenantId);
}
