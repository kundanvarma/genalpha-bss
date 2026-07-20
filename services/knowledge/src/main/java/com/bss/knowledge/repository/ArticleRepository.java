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
    /** cfg is the tenant's stemmer ('english', 'norwegian', …) — the
     * expression matches the language GIN indexes exactly. */
    @Query(value = "SELECT * FROM article a WHERE a.tenant_id = :tenantId AND ("
            + " to_tsvector(CAST(:cfg AS regconfig), a.title || ' ' || a.body || ' ' || coalesce(a.tags, ''))"
            + "   @@ plainto_tsquery(CAST(:cfg AS regconfig), :q)"
            + " OR a.title ILIKE '%' || :q || '%' OR a.tags ILIKE '%' || :q || '%')"
            + " ORDER BY ts_rank(to_tsvector(CAST(:cfg AS regconfig), a.title || ' ' || a.body || ' '"
            + "   || coalesce(a.tags, '')), plainto_tsquery(CAST(:cfg AS regconfig), :q)) DESC",
            nativeQuery = true)
    List<Article> search(@Param("tenantId") String tenantId, @Param("q") String q,
            @Param("cfg") String cfg);

    /** The vector lives OUTSIDE the entity (H2 unit tests never see it);
     * writes and reads are native, Postgres-only, caller-caught. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query(value = "UPDATE article SET embedding = CAST(:vec AS vector)"
            + " WHERE id = :id AND tenant_id = :tenantId", nativeQuery = true)
    int storeEmbedding(@Param("tenantId") String tenantId, @Param("id") String id,
            @Param("vec") String vec);

    /** Cosine nearest neighbours under a ceiling — the SEMANTIC NET. */
    @Query(value = "SELECT * FROM article a WHERE a.tenant_id = :tenantId"
            + " AND a.embedding IS NOT NULL"
            + " AND (a.embedding <=> CAST(:vec AS vector)) < :ceiling"
            + " ORDER BY a.embedding <=> CAST(:vec AS vector) LIMIT 5", nativeQuery = true)
    List<Article> searchSemantic(@Param("tenantId") String tenantId,
            @Param("vec") String vec, @Param("ceiling") double ceiling);
}
