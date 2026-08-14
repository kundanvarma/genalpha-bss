package com.bss.insight.repository;

import com.bss.insight.entity.SocialMention;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SocialMentionRepository extends JpaRepository<SocialMention, String> {

    boolean existsByTenantIdAndPlatformAndExternalId(String tenantId, String platform, String externalId);

    List<SocialMention> findByTenantIdOrderByCreatedAtDesc(String tenantId);
}
