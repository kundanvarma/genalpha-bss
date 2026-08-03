package com.bss.process.repository;

import com.bss.process.entity.ProcessEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProcessEventRepository extends JpaRepository<ProcessEvent, String> {
    List<ProcessEvent> findAllByTenantIdAndProcessFlowIdOrderByEventTimeAsc(String tenantId, String processFlowId);
}
