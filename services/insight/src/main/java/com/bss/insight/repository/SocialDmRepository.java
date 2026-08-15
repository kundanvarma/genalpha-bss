package com.bss.insight.repository;

import com.bss.insight.entity.SocialDm;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SocialDmRepository extends JpaRepository<SocialDm, String> {

    boolean existsByTenantIdAndPlatformAndExternalId(String tenantId, String platform, String externalId);

    List<SocialDm> findByTenantIdOrderByCreatedAtDesc(String tenantId);
}
