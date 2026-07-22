package com.bss.insight.repository;

import com.bss.insight.entity.VisitorEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface VisitorEventRepository extends JpaRepository<VisitorEvent, String> {

    /** Interests, strongest first: category view counts for one visitor. */
    @Query("select e.category, count(e) from VisitorEvent e"
            + " where e.tenantId = :tenantId and e.visitorId = :visitorId"
            + " and e.category is not null group by e.category order by count(e) desc")
    List<Object[]> interestsOf(@Param("tenantId") String tenantId,
            @Param("visitorId") String visitorId);

    long countByTenantIdAndVisitorId(String tenantId, String visitorId);

    void deleteByTenantIdAndVisitorId(String tenantId, String visitorId);

    /** NEXT-HIT: the categories this visitor viewed, MOST RECENT FIRST —
     * recency, not frequency, so the last look leads the next page. The
     * caller dedups (keeping first occurrence) and bounds by the session
     * window. */
    @Query("select e.category from VisitorEvent e"
            + " where e.tenantId = :tenantId and e.visitorId = :visitorId"
            + " and e.category is not null and e.type = 'view' and e.createdAt >= :since"
            + " order by e.createdAt desc")
    List<String> recentCategoryViews(@Param("tenantId") String tenantId,
            @Param("visitorId") String visitorId,
            @Param("since") java.time.OffsetDateTime since);

    /** NEXT-HIT: the offerings this visitor just looked at, most recent
     * first — the "pick up where you left off" rail. */
    @Query("select e.offeringId from VisitorEvent e"
            + " where e.tenantId = :tenantId and e.visitorId = :visitorId"
            + " and e.offeringId is not null and e.type = 'view' and e.createdAt >= :since"
            + " order by e.createdAt desc")
    List<String> recentOfferingViews(@Param("tenantId") String tenantId,
            @Param("visitorId") String visitorId,
            @Param("since") java.time.OffsetDateTime since);
}
