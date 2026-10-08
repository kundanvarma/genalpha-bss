package com.bss.knowledge.service;

import com.bss.knowledge.api.ApiConstants;
import com.bss.knowledge.api.Json;
import com.bss.knowledge.dto.ArticleRequest;
import com.bss.knowledge.dto.ArticleView;
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
import java.util.LinkedHashSet;
import java.util.List;
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
    /**
     * The shelves. This table is PRINTED in the refusal a bad audience gets,
     * so the order is the one the wire already has — a {@code Set.of} was
     * re-salting it on every JVM start.
     */
    private static final Set<String> AUDIENCES = new LinkedHashSet<>(
            List.of("public", "customer", "csr", "productOwner", "all", "sales"));

    /**
     * THE ONLY SHELF AN ANONYMOUS READER SEES, and it is empty until somebody
     * decides otherwise.
     *
     * "customer" has never meant the public — it means a SIGNED-IN customer, so
     * making help crawlable could not reuse it without publishing 43 articles
     * nobody reviewed for the open internet (#225). `public` is a separate,
     * explicit shelf: an article reaches it only when an author sets this
     * audience on that article, one at a time.
     *
     * Deliberately NOT a default and NOT inherited: no migration moves an
     * article here, "all" does not include it, and a new article does not land
     * here by omission. Publishing support material is an operator's decision
     * about what their words say in the open, and the safe direction for a
     * mistake is an empty page rather than a disclosure.
     */
    public static final String PUBLIC_AUDIENCE = "public";

    private final ArticleRepository repository;
    private final TenantScope tenantScope;
    private final com.bss.knowledge.security.TenantRegistry tenants;
    private final com.bss.knowledge.embed.EmbeddingProvider embeddings;
    private final DomainEventPublisher events;

    public ArticleService(ArticleRepository repository, TenantScope tenantScope,
            DomainEventPublisher events,
            com.bss.knowledge.security.TenantRegistry tenants,
            com.bss.knowledge.embed.EmbeddingProvider embeddings) {
        this.repository = repository;
        this.tenantScope = tenantScope;
        this.tenants = tenants;
        this.embeddings = embeddings;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<ArticleView> find(String q, String category, String audience) {
        return find(q, category, audience, null);
    }

    /** With a context TAG (e.g. "pane:approvals", "csr:tickets", "shop:bills"): the shelf for
     *  one screen — the audience gate still applies, a customer never sees a product how-to. */
    @Transactional(readOnly = true)
    public List<ArticleView> find(String q, String category, String audience, String tag) {
        String tenantId = tenantScope.currentTenantId();
        List<Article> hits = (q == null || q.isBlank())
                ? repository.findByTenantIdOrderByLastUpdateDesc(tenantId)
                : repository.search(tenantId, q.trim(), stemmerOf(tenantId));
        if (hits.isEmpty() && q != null && !q.isBlank()) {
            // THE SEMANTIC NET: cosine neighbours speak only when keyword
            // search is silent — FTS behavior is untouched by construction.
            // Fail-open: no pgvector (H2, bare Postgres) → silence stands.
            try {
                hits = repository.searchSemantic(tenantId,
                        vectorLiteral(embeddings.embed(q)), embeddings.ceiling());
            } catch (Exception semanticUnavailable) {
                // the keyword (empty) answer stands
            }
        }
        Set<String> readable = readableAudiences();
        boolean author = isAuthor();
        return hits.stream()
                .filter(a -> readable.contains(a.getAudience()))
                .filter(a -> author || "published".equals(a.getStatus()))
                .filter(a -> category == null || category.equals(a.getCategory()))
                .filter(a -> audience == null || audience.equals(a.getAudience()))
                .filter(a -> tag == null || hasTag(a, tag))
                .map(this::toView)
                .toList();
    }

    private static boolean hasTag(Article a, String tag) {
        if (a.getTags() == null) {
            return false;
        }
        for (String t : a.getTags().split(",")) {
            if (t.trim().equalsIgnoreCase(tag.trim())) {
                return true;
            }
        }
        return false;
    }

    @Transactional(readOnly = true)
    /**
     * THE ANONYMOUS DOOR. Published articles on the `public` shelf and nothing
     * else — no token, no audience negotiation, no author preview.
     *
     * It does not call readableAudiences(), and that is the point: this path
     * has no caller to ask, so the shelf is hard-coded rather than derived.
     * A reader who is nobody must not be able to widen it by what they send.
     *
     * Today it returns an empty list, because no article carries the audience.
     * That is the correct state to ship: the mechanism is reviewable now, and
     * what appears through it stays an operator's decision, taken one article
     * at a time (#225).
     */
    public List<ArticleView> findPublic(String q, String category, String tag) {
        String tenantId = tenantScope.currentTenantId();
        List<Article> hits = repository.findByTenantIdOrderByLastUpdateDesc(tenantId);
        String needle = q == null ? null : q.trim().toLowerCase();
        return hits.stream()
                .filter(a -> PUBLIC_AUDIENCE.equals(a.getAudience()))
                .filter(a -> "published".equals(a.getStatus()))
                .filter(a -> category == null || category.equals(a.getCategory()))
                .filter(a -> tag == null || hasTag(a, tag))
                // keyword match in Java, not SQL: the anonymous path takes no
                // caller-shaped query into the database
                .filter(a -> needle == null || needle.isEmpty()
                        || (a.getTitle() != null && a.getTitle().toLowerCase().contains(needle))
                        || (a.getBody() != null && a.getBody().toLowerCase().contains(needle)))
                .map(this::toView)
                .toList();
    }

    public ArticleView findById(String id) {
        Article a = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        if (!readableAudiences().contains(a.getAudience())
                || (!isAuthor() && !"published".equals(a.getStatus()))) {
            throw NotFoundException.forResource(RESOURCE, id);
        }
        return toView(a);
    }

    @Transactional
    public ArticleView create(ArticleRequest dto) {
        Article a = new Article();
        String id = Json.present(dto.id()) && !Json.valueOfLike(dto.id()).isBlank()
                ? Json.valueOfLike(dto.id()) : UUID.randomUUID().toString();
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
        ArticleView created = toView(repository.save(a));
        embedQuietly(a);
        events.publish("ArticleCreateEvent", "article", created);
        return created;
    }

    @Transactional
    public ArticleView patch(String id, ArticleRequest dto) {
        Article a = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        apply(dto, a);
        a.setLastUpdate(OffsetDateTime.now());
        ArticleView updated = toView(repository.save(a));
        embedQuietly(a);
        events.publish("ArticleAttributeValueChangeEvent", "article", updated);
        return updated;
    }

    @Transactional
    public void delete(String id) {
        Article a = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        repository.delete(a);
        events.publish("ArticleDeleteEvent", "article", toView(a));
    }

    /** A key the caller did not send — and a key they sent as null — leaves the article alone. */
    private void apply(ArticleRequest dto, Article a) {
        if (Json.present(dto.title())) {
            a.setTitle(Json.valueOfLike(dto.title()));
        }
        if (Json.present(dto.body())) {
            a.setBody(Json.valueOfLike(dto.body()));
        }
        if (Json.present(dto.tags())) {
            a.setTags(Json.valueOfLike(dto.tags()));
        }
        if (Json.present(dto.category())) {
            a.setCategory(Json.valueOfLike(dto.category()));
        }
        if (Json.present(dto.audience())) {
            String audience = Json.valueOfLike(dto.audience());
            if (!AUDIENCES.contains(audience)) {
                throw new BadRequestException("audience must be one of " + AUDIENCES);
            }
            a.setAudience(audience);
        }
        if (Json.present(dto.status())) {
            a.setStatus(Json.valueOfLike(dto.status()));
        }
    }

    /** The shelf the caller's token unlocks. */
    private Set<String> readableAudiences() {
        Set<String> authorities = authorities();
        if (authorities.contains("knowledge:write")) {
            return AUDIENCES;
        }
        // the public shelf is the most open one there is, so anybody who can
        // read anything can read it too — it is added to each shelf below
        // rather than replacing one
        if (authorities.contains("customer")) {
            // THE MODULE WALL: a customer's self-service authorities (billing:write,
            // ticket:write…) look like staff ones — the customer role decides, first
            return Set.of(PUBLIC_AUDIENCE, "customer", "all");
        }
        if (authorities.contains("catalog:write") || authorities.contains("campaign:write")
                || authorities.contains("billing:write") || authorities.contains("catalog:approve")) {
            // back-office staff (product, marketing, finance, approvers): their
            // how-tos plus everything customer-facing — never a customer
            return Set.of(PUBLIC_AUDIENCE, "customer", "csr", "sales", "productOwner", "all");
        }
        if (authorities.contains("ticket:write") || authorities.contains("wholesale:admin")) {
            return Set.of(PUBLIC_AUDIENCE, "customer", "csr", "sales", "all");
        }
        if (authorities.contains("agent")) {
            return Set.of(PUBLIC_AUDIENCE, "customer", "csr", "sales", "all");
        }
        return Set.of(PUBLIC_AUDIENCE, "customer", "all");
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

    private ArticleView toView(Article a) {
        return new ArticleView(a.getId(), a.getHref(), a.getTitle(), a.getBody(), a.getTags(),
                a.getCategory(), a.getAudience(), a.getStatus(), a.getLastUpdate());
    }

    /** The tenant's language picks the stemmer — Norwegian articles are
     * searched in Norwegian ("regning" finds "regningene"). */
    private String stemmerOf(String tenantId) {
        com.bss.knowledge.security.TenantRegistry.TenantEntry tenant = tenants.byId(tenantId);
        String locale = tenant == null || tenant.getLocale() == null ? "en" : tenant.getLocale();
        return switch (locale.toLowerCase().split("[-_]")[0]) {
            case "no", "nb", "nn" -> "norwegian";
            case "da" -> "danish";
            case "sv" -> "swedish";
            case "de" -> "german";
            case "fr" -> "french";
            default -> "english";
        };
    }

    /** Embed on save, quietly: a failed embedding never blocks an
     * article — it just will not be found semantically until re-saved. */
    private void embedQuietly(Article a) {
        try {
            String text = a.getTitle() + " " + a.getBody()
                    + (a.getTags() == null ? "" : " " + a.getTags());
            repository.storeEmbedding(a.getTenantId(), a.getId(),
                    vectorLiteral(embeddings.embed(text)));
        } catch (Exception embeddingUnavailable) {
            // fail-open: the article stands, unembedded
        }
    }

    private static String vectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
