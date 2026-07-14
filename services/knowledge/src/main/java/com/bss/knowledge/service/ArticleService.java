package com.bss.knowledge.service;

import com.bss.knowledge.api.ApiConstants;
import com.bss.knowledge.entity.Article;
import com.bss.knowledge.events.DomainEventPublisher;
import com.bss.knowledge.exception.BadRequestException;
import com.bss.knowledge.exception.NotFoundException;
import com.bss.knowledge.repository.ArticleRepository;
import com.bss.knowledge.security.TenantScope;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The library's one rule: WHO you are decides WHAT you see. Customers get
 * customer articles, agents get the CSR shelf on top, catalog people get the
 * product-owner how-tos, authors see everything including drafts. The
 * audience filter is applied here, from the token — never trusted from a
 * query parameter.
 */
@Service
public class ArticleService {

    private static final String RESOURCE = "Article";
    private static final Set<String> AUDIENCES =
            Set.of("customer", "csr", "sales", "productOwner", "all");

    private final ArticleRepository repository;
    private final TenantScope tenantScope;
    private final DomainEventPublisher events;

    public ArticleService(ArticleRepository repository, TenantScope tenantScope,
            DomainEventPublisher events) {
        this.repository = repository;
        this.tenantScope = tenantScope;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> find(String q, String category, String audience) {
        String tenantId = tenantScope.currentTenantId();
        List<Article> hits = (q == null || q.isBlank())
                ? repository.findByTenantIdOrderByLastUpdateDesc(tenantId)
                : repository.search(tenantId, q.trim());
        Set<String> readable = readableAudiences();
        boolean author = isAuthor();
        return hits.stream()
                .filter(a -> readable.contains(a.getAudience()))
                .filter(a -> author || "published".equals(a.getStatus()))
                .filter(a -> category == null || category.equals(a.getCategory()))
                .filter(a -> audience == null || audience.equals(a.getAudience()))
                .map(this::toMap)
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findById(String id) {
        Article a = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        if (!readableAudiences().contains(a.getAudience())
                || (!isAuthor() && !"published".equals(a.getStatus()))) {
            throw NotFoundException.forResource(RESOURCE, id);
        }
        return toMap(a);
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> dto) {
        Article a = new Article();
        String id = dto.get("id") != null && !String.valueOf(dto.get("id")).isBlank()
                ? String.valueOf(dto.get("id")) : UUID.randomUUID().toString();
        a.setId(id);
        a.setTenantId(tenantScope.currentTenantId());
        a.setHref(ApiConstants.BASE_PATH + "/article/" + id);
        apply(dto, a);
        if (a.getTitle() == null || a.getTitle().isBlank()
                || a.getBody() == null || a.getBody().isBlank()) {
            throw new BadRequestException("title and body are required");
        }
        if (a.getAudience() == null) {
            a.setAudience("customer");
        }
        if (a.getStatus() == null) {
            a.setStatus("published");
        }
        a.setCreatedAt(OffsetDateTime.now());
        a.setLastUpdate(OffsetDateTime.now());
        Map<String, Object> created = toMap(repository.save(a));
        events.publish("ArticleCreateEvent", "article", created);
        return created;
    }

    @Transactional
    public Map<String, Object> patch(String id, Map<String, Object> dto) {
        Article a = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        apply(dto, a);
        a.setLastUpdate(OffsetDateTime.now());
        Map<String, Object> updated = toMap(repository.save(a));
        events.publish("ArticleAttributeValueChangeEvent", "article", updated);
        return updated;
    }

    @Transactional
    public void delete(String id) {
        Article a = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        repository.delete(a);
        events.publish("ArticleDeleteEvent", "article", toMap(a));
    }

    private void apply(Map<String, Object> dto, Article a) {
        if (dto.get("title") != null) {
            a.setTitle(String.valueOf(dto.get("title")));
        }
        if (dto.get("body") != null) {
            a.setBody(String.valueOf(dto.get("body")));
        }
        if (dto.get("tags") != null) {
            a.setTags(String.valueOf(dto.get("tags")));
        }
        if (dto.get("category") != null) {
            a.setCategory(String.valueOf(dto.get("category")));
        }
        if (dto.get("audience") != null) {
            String audience = String.valueOf(dto.get("audience"));
            if (!AUDIENCES.contains(audience)) {
                throw new BadRequestException("audience must be one of " + AUDIENCES);
            }
            a.setAudience(audience);
        }
        if (dto.get("status") != null) {
            a.setStatus(String.valueOf(dto.get("status")));
        }
    }

    /** The shelf the caller's token unlocks. */
    private Set<String> readableAudiences() {
        Set<String> authorities = authorities();
        if (authorities.contains("knowledge:write")) {
            return AUDIENCES;
        }
        if (authorities.contains("catalog:write")) {
            // product owners: their how-tos plus everything customer-facing
            return Set.of("customer", "csr", "sales", "productOwner", "all");
        }
        if (authorities.contains("agent")) {
            return Set.of("customer", "csr", "sales", "all");
        }
        return Set.of("customer", "all");
    }

    private boolean isAuthor() {
        return authorities().contains("knowledge:write");
    }

    private Set<String> authorities() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return Set.of();
        }
        Set<String> out = new java.util.HashSet<>();
        for (GrantedAuthority ga : auth.getAuthorities()) {
            out.add(ga.getAuthority());
        }
        return out;
    }

    private Map<String, Object> toMap(Article a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("href", a.getHref());
        m.put("title", a.getTitle());
        m.put("body", a.getBody());
        m.put("tags", a.getTags());
        m.put("category", a.getCategory());
        m.put("audience", a.getAudience());
        m.put("status", a.getStatus());
        m.put("lastUpdate", a.getLastUpdate());
        m.put("@type", "Article");
        return m;
    }
}
