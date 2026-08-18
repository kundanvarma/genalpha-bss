package com.bss.address.repository;

import com.bss.address.entity.RegistryLookupLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RegistryLookupLogRepository extends JpaRepository<RegistryLookupLog, String> {
}
