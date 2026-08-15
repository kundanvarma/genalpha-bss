package com.bss.insight.repository;

import com.bss.insight.entity.EmailSuppression;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EmailSuppressionRepository extends JpaRepository<EmailSuppression, String> {

    boolean existsByTenantIdAndEmail(String tenantId, String email);

    List<EmailSuppression> findByTenantId(String tenantId);
}
