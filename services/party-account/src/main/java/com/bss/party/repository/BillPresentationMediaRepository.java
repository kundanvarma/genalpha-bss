package com.bss.party.repository;

import com.bss.party.entity.BillPresentationMedia;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BillPresentationMediaRepository extends JpaRepository<BillPresentationMedia, String> {

    Optional<BillPresentationMedia> findByIdAndTenantId(String id, String tenantId);
}
