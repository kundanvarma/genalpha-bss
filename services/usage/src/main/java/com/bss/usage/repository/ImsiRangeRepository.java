package com.bss.usage.repository;

import com.bss.usage.entity.ImsiRange;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ImsiRangeRepository extends JpaRepository<ImsiRange, String> {
    List<ImsiRange> findByTenantId(String tenantId);
}
