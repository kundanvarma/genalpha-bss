package com.bss.usage.repository;

import com.bss.usage.entity.UsageSpecification;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UsageSpecificationRepository extends JpaRepository<UsageSpecification, String> {
}
