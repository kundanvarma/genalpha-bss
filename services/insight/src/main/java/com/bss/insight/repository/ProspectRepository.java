package com.bss.insight.repository;

import com.bss.insight.entity.Prospect;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProspectRepository extends JpaRepository<Prospect, String> {

    Optional<Prospect> findByTenantIdAndEmail(String tenantId, String email);

    List<Prospect> findByTenantId(String tenantId);
}
