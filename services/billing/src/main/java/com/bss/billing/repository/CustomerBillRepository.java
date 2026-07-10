package com.bss.billing.repository;

import com.bss.billing.entity.CustomerBill;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;

public interface CustomerBillRepository extends JpaRepository<CustomerBill, String> {

    boolean existsByOwnerPartyIdAndPeriodStart(String ownerPartyId, LocalDate periodStart);
}
