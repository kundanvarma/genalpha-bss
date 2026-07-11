package com.bss.agreement.repository;

import com.bss.agreement.entity.Agreement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AgreementRepository extends JpaRepository<Agreement, String> {

    Optional<Agreement> findByIdAndTenantId(String id, String tenantId);
}
