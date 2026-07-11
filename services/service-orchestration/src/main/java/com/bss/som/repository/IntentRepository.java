package com.bss.som.repository;

import com.bss.som.entity.Intent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IntentRepository extends JpaRepository<Intent, String> {

    Optional<Intent> findByIdAndTenantId(String id, String tenantId);

    List<Intent> findByTenantIdOrderByCreatedAtDesc(String tenantId);
}
