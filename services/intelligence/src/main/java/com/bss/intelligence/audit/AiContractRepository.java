package com.bss.intelligence.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiContractRepository extends JpaRepository<AiContract, String> {

    List<AiContract> findByTenantId(String tenantId);

    Optional<AiContract> findByTenantIdAndUseCase(String tenantId, String useCase);
}
