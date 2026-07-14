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
}
