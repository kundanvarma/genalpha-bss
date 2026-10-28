package com.bss.benefit.repository;

import com.bss.benefit.entity.PartnerInvoice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PartnerInvoiceRepository extends JpaRepository<PartnerInvoice, String> {

    Optional<PartnerInvoice> findByTenantIdAndPartnerAndPeriodAndReference(String tenantId, String partner,
            String period, String reference);

    Optional<PartnerInvoice> findByIdAndTenantId(String id, String tenantId);

    List<PartnerInvoice> findByTenantIdAndPeriodOrderByPartnerAscRecordedAtAsc(String tenantId, String period);
}
