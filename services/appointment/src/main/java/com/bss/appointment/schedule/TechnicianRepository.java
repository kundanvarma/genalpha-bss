package com.bss.appointment.schedule;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TechnicianRepository extends JpaRepository<Technician, String> {

    List<Technician> findByTenantIdOrderByName(String tenantId);

    Optional<Technician> findByIdAndTenantId(String id, String tenantId);
}
