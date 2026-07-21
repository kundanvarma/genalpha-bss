package com.bss.appointment.repository;

import com.bss.appointment.entity.Appointment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface AppointmentRepository extends JpaRepository<Appointment, String> {

    Optional<Appointment> findByIdAndTenantId(String id, String tenantId);

    @Query("select count(a) from Appointment a where a.startAt = :start and a.status = 'confirmed'"
            + " and a.tenantId = :tenantId")
    long confirmedAt(@Param("start") OffsetDateTime start, @Param("tenantId") String tenantId);

    java.util.List<Appointment> findByTenantIdAndOwnerPartyId(String tenantId, String ownerPartyId);
}
