package com.bss.intelligence.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface KnowledgeGapRepository extends JpaRepository<KnowledgeGap, String> {

    Optional<KnowledgeGap> findByTenantIdAndQuestion(String tenantId, String question);

    List<KnowledgeGap> findTop50ByTenantIdOrderByAskedDescLastAskedDesc(String tenantId);
}
