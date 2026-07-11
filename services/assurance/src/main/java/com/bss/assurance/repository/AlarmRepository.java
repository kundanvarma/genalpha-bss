package com.bss.assurance.repository;

import com.bss.assurance.entity.Alarm;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AlarmRepository extends JpaRepository<Alarm, String> {

    Optional<Alarm> findByIdAndTenantId(String id, String tenantId);

    List<Alarm> findByTenantIdAndState(String tenantId, String state);
}
