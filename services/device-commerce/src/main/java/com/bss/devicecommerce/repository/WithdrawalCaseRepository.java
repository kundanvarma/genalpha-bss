package com.bss.devicecommerce.repository;

import com.bss.devicecommerce.entity.WithdrawalCase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WithdrawalCaseRepository extends JpaRepository<WithdrawalCase, String> {

    Optional<WithdrawalCase> findByIdAndTenantId(String id, String tenantId);

    List<WithdrawalCase> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    Optional<WithdrawalCase> findByTenantIdAndAgreementRef(String tenantId, String agreementRef);
}
