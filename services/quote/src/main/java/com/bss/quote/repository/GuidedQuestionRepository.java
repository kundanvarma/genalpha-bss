package com.bss.quote.repository;

import com.bss.quote.entity.GuidedQuestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GuidedQuestionRepository extends JpaRepository<GuidedQuestion, String> {
    List<GuidedQuestion> findByTenantIdOrderBySortOrderAscCreatedAtAsc(String tenantId);
}
