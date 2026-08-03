package com.bss.process.repository;

import com.bss.process.entity.TaskFlow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TaskFlowRepository extends JpaRepository<TaskFlow, String> {
    List<TaskFlow> findAllByTenantIdAndProcessFlowIdOrderBySeqAsc(String tenantId, String processFlowId);
    Optional<TaskFlow> findByIdAndTenantId(String id, String tenantId);
}
