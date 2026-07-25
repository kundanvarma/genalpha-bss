package com.bss.usage.repository;

import com.bss.usage.entity.PrepayTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PrepayTaskRepository extends JpaRepository<PrepayTask, String> {

    Optional<PrepayTask> findByIdAndTenantIdAndResourceType(String id, String tenantId, String resourceType);

    List<PrepayTask> findAllByTenantIdAndResourceTypeOrderByCreatedAtAsc(String tenantId, String resourceType);
}
