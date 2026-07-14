package com.bss.knowledge.repository;

import com.bss.knowledge.entity.Article;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ArticleRepository extends JpaRepository<Article, String> {

    Optional<Article> findByIdAndTenantId(String id, String tenantId);

    List<Article> findByTenantIdOrderByLastUpdateDesc(String tenantId);

    /**
     * Postgres full-text search over title+body+tags, ILIKE as the safety
     * net for word fragments FTS stems away. Ranked: best match first.
     */
    @Query(value = "SELECT * FROM article a WHERE a.tenant_id = :tenantId AND ("
            + " to_tsvector('english', a.title || ' ' || a.body || ' ' || coalesce(a.tags, ''))"
            + "   @@ plainto_tsquery('english', :q)"
            + " OR a.title ILIKE '%' || :q || '%' OR a.tags ILIKE '%' || :q || '%')"
            + " ORDER BY ts_rank(to_tsvector('english', a.title || ' ' || a.body || ' '"
            + "   || coalesce(a.tags, '')), plainto_tsquery('english', :q)) DESC",
            nativeQuery = true)
    List<Article> search(@Param("tenantId") String tenantId, @Param("q") String q);
}
