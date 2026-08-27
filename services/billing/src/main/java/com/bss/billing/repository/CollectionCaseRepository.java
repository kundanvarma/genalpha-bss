package com.bss.billing.repository;

import com.bss.billing.entity.CollectionCase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CollectionCaseRepository extends JpaRepository<CollectionCase, String> {

    Optional<CollectionCase> findByIdAndTenantId(String id, String tenantId);

    Optional<CollectionCase> findByTenantIdAndAccountId(String tenantId, String accountId);

    List<CollectionCase> findByTenantId(String tenantId);

    List<CollectionCase> findByTenantIdAndState(String tenantId, String state);
}
