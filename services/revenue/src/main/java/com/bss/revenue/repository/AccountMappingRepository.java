package com.bss.revenue.repository;

import com.bss.revenue.entity.AccountMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AccountMappingRepository extends JpaRepository<AccountMapping, AccountMapping.Key> {

    List<AccountMapping> findAllByTenantIdOrderByMappingKeyAsc(String tenantId);

    Optional<AccountMapping> findByTenantIdAndMappingKey(String tenantId, String mappingKey);
}
