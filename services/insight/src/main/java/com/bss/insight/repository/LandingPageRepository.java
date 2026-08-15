package com.bss.insight.repository;

import com.bss.insight.entity.LandingPage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LandingPageRepository extends JpaRepository<LandingPage, String> {

    Optional<LandingPage> findByTenantIdAndSlug(String tenantId, String slug);

    List<LandingPage> findByTenantIdOrderByCreatedAtDesc(String tenantId);
}
