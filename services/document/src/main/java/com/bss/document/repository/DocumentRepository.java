package com.bss.document.repository;

import com.bss.document.entity.StoredDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DocumentRepository extends JpaRepository<StoredDocument, String> {

    Optional<StoredDocument> findByIdAndTenantId(String id, String tenantId);

    List<StoredDocument> findByTenantIdAndCategory(String tenantId, String category);

    List<StoredDocument> findByTenantId(String tenantId);
}
