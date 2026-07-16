package com.bss.som.repository;

import com.bss.som.entity.TelesalesOffer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface TelesalesOfferRepository extends JpaRepository<TelesalesOffer, String> {
    Optional<TelesalesOffer> findByTenantIdAndConfirmToken(String tenantId, String token);
    List<TelesalesOffer> findTop100ByTenantIdAndStatusAndExpiresAtBefore(
            String tenantId, String status, OffsetDateTime cutoff);
    List<TelesalesOffer> findTop100ByTenantIdAndDealerOrgIdOrderByCreatedAtDesc(
            String tenantId, String dealerOrgId);
}
