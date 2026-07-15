package com.bss.communication.repository;

import com.bss.communication.entity.Suppression;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SuppressionRepository extends JpaRepository<Suppression, String> {

    boolean existsByTenantIdAndEmail(String tenantId, String email);

    List<Suppression> findByTenantIdOrderByCreatedAtDesc(String tenantId);
}
