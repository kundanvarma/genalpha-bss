package com.bss.usage.repository;

import com.bss.usage.entity.RatedCharge;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface RatedChargeRepository extends JpaRepository<RatedCharge, String> {

    List<RatedCharge> findByOwnerPartyIdAndPeriodStart(String ownerPartyId, LocalDate periodStart);
}
