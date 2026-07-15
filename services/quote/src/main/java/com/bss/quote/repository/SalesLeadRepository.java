package com.bss.quote.repository;

import com.bss.quote.entity.SalesLead;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SalesLeadRepository extends JpaRepository<SalesLead, String> {

    List<SalesLead> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    Optional<SalesLead> findByIdAndTenantId(String id, String tenantId);
}
