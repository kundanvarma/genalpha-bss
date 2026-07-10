package com.bss.appointment.repository;

import com.bss.appointment.entity.Appointment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;

public interface AppointmentRepository extends JpaRepository<Appointment, String> {

    @Query("select count(a) from Appointment a where a.startAt = :start and a.status = 'confirmed'")
    long confirmedAt(@Param("start") OffsetDateTime start);
}
