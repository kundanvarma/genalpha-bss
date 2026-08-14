package com.bss.communication.repository;

import com.bss.communication.entity.MessageTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MessageTemplateRepository extends JpaRepository<MessageTemplate, String> {

    List<MessageTemplate> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    Optional<MessageTemplate> findByIdAndTenantId(String id, String tenantId);
}
