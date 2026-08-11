package com.bss.document.repository;

import com.bss.document.entity.ContentProviderConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ContentProviderConfigRepository extends JpaRepository<ContentProviderConfig, String> {

    /** RLS already scopes to the current tenant; the id predicate is belt-and-braces. */
    Optional<ContentProviderConfig> findByTenantId(String tenantId);
}
