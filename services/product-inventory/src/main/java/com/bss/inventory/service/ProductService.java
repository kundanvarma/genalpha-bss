package com.bss.inventory.service;

import com.bss.inventory.api.ApiConstants;
import com.bss.inventory.api.OffsetPageRequest;
import com.bss.inventory.api.PagedResult;
import com.bss.inventory.dto.ProductDto;
import com.bss.inventory.entity.Product;
import com.bss.inventory.events.DomainEventPublisher;
import com.bss.inventory.exception.BadRequestException;
import com.bss.inventory.exception.NotFoundException;
import com.bss.inventory.mapper.ProductMapper;
import com.bss.inventory.repository.ProductRepository;
import com.bss.inventory.security.PartyScope;
import com.bss.inventory.security.TenantScope;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ProductService {

    private static final String RESOURCE = "Product";

    private final ProductRepository repository;
    private final ProductMapper mapper;
    private final DomainEventPublisher events;
    private final PartyScope partyScope;
    private final TenantScope tenantScope;
    private final com.bss.inventory.client.PartyClient partyClient;

    public ProductService(ProductRepository repository, ProductMapper mapper,
            DomainEventPublisher events, PartyScope partyScope, TenantScope tenantScope,
            com.bss.inventory.client.PartyClient partyClient) {
        this.repository = repository;
        this.mapper = mapper;
        this.events = events;
        this.partyScope = partyScope;
        this.tenantScope = tenantScope;
        this.partyClient = partyClient;
    }

    @Transactional(readOnly = true)
    public PagedResult<ProductDto> findAll(int offset, int limit, Map<String, String> filters) {
        // A plain "my products" list includes what the party PAYS for too —
        // a parent sees the child's plan they fund (labelled by the channel).
        // Filtered queries keep the strict owner scope.
        java.util.Optional<String> scoped = partyScope.scopedPartyId();
        if (scoped.isPresent() && filters.isEmpty()) {
            Page<Product> visible = repository.findVisibleToParty(
                    tenantScope.currentTenantId(), scoped.get(), new OffsetPageRequest(offset, limit));
            return new PagedResult<>(visible.getContent().stream().map(mapper::toDto).toList(),
                    visible.getTotalElements());
        }
        // A scoped customer asking for ANOTHER party's products is the family
        // hub: allowed only across a live household link, verified at the
        // party source — and even then it shows what the family FUNDS, unless
        // the member is a child account (a child's line is fully visible).
        if (scoped.isPresent() && filters.size() == 1
                && filters.containsKey("relatedPartyId")
                && !scoped.get().equals(filters.get("relatedPartyId"))) {
            return familyView(scoped.get(), filters.get("relatedPartyId"), offset, limit);
        }
        Product probe = probeFor(filters);
        probe.setTenantId(tenantScope.currentTenantId());
        // Customers see their own products only, whatever else they filter on.
        scoped.ifPresent(probe::setOwnerPartyId);
        Page<Product> page = repository.findAll(Example.of(probe), new OffsetPageRequest(offset, limit));
        return new PagedResult<>(page.getContent().stream().map(mapper::toDto).toList(), page.getTotalElements());
    }

    /**
     * The guardian's window: the caller must be the member's ACTIVE household
     * payer, or an ACTIVE ADMIN of the same household. Everything is checked
     * live against the party source; anything short of a live link is a 404 —
     * foreign ids never learn they exist. Adult members show only what the
     * family pays for; child accounts show their whole line.
     */
    private PagedResult<ProductDto> familyView(String callerId, String memberId, int offset, int limit) {
        Map<String, Object> memberLink = partyClient.householdLinkOf(memberId).orElse(null);
        if (memberLink == null || !"active".equals(memberLink.get("status"))) {
            throw NotFoundException.forResource(RESOURCE, memberId);
        }
        String payerId = String.valueOf(memberLink.get("id"));
        boolean callerIsPayer = callerId.equals(payerId);
        boolean callerIsAdmin = !callerIsPayer && partyClient.householdLinkOf(callerId)
                .filter(l -> payerId.equals(String.valueOf(l.get("id"))))
                .filter(l -> "active".equals(l.get("status")))
                .filter(l -> "admin".equals(l.get("role")))
                .isPresent();
        if (!callerIsPayer && !callerIsAdmin) {
            throw NotFoundException.forResource(RESOURCE, memberId);
        }
        Page<Product> page = "child".equals(memberLink.get("role"))
                ? repository.findByTenantIdAndOwnerPartyId(
                        tenantScope.currentTenantId(), memberId, new OffsetPageRequest(offset, limit))
                : repository.findFundedFor(
                        tenantScope.currentTenantId(), memberId, payerId, new OffsetPageRequest(offset, limit));
        return new PagedResult<>(page.getContent().stream().map(mapper::toDto).toList(), page.getTotalElements());
    }

    /**
     * TMF630 attribute filtering: exact match on scalar attributes via
     * query-by-example. Unknown attributes are rejected rather than silently
     * matching everything.
     */
    private Product probeFor(Map<String, String> filters) {
        Product probe = new Product();
        for (Map.Entry<String, String> f : filters.entrySet()) {
            switch (f.getKey()) {
                case "id" -> probe.setId(f.getValue());
                case "name" -> probe.setName(f.getValue());
                case "status" -> probe.setStatus(f.getValue());
                case "relatedPartyId" -> probe.setOwnerPartyId(f.getValue());
                default -> throw new BadRequestException("unsupported filter attribute '" + f.getKey() + "'");
            }
        }
        return probe;
    }

    @Transactional(readOnly = true)
    public ProductDto findById(String id) {
        Product entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        requireOwn(entity);
        return mapper.toDto(entity);
    }

    @Transactional
    public ProductDto create(ProductDto dto) {
        if (dto.getStatus() == null) {
            dto.setStatus("created");
        }
        Product entity = mapper.toEntity(dto);
        entity.setTenantId(tenantScope.currentTenantId());
        entity.setOwnerPartyId(partyScope.scopedPartyId().orElseGet(() -> customerPartyIn(dto.getRelatedParty())));
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setHref(ApiConstants.BASE_PATH + "/product/" + id);
        // TMF637 startDate: the posted value wins (migrations, backdating);
        // otherwise the product starts NOW — billing prorates from here
        if (dto.getStartDate() != null) {
            try {
                entity.setStartDate(java.time.OffsetDateTime.parse(dto.getStartDate()));
            } catch (Exception e) {
                entity.setStartDate(java.time.OffsetDateTime.now());
            }
        } else {
            entity.setStartDate(java.time.OffsetDateTime.now());
        }
        ProductDto created = mapper.toDto(repository.save(entity));
        events.publish("ProductCreateEvent", "product", created);
        return created;
    }

    @Transactional
    public ProductDto patch(String id, ProductDto patch) {
        Product entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        requireOwn(entity);
        mapper.applyPatch(patch, entity);
        ProductDto updated = mapper.toDto(repository.save(entity));
        events.publish("ProductAttributeValueChangeEvent", "product", updated);
        return updated;
    }

    @Transactional
    public void delete(String id) {
        Product entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        requireOwn(entity);
        ProductDto deleted = mapper.toDto(entity);
        repository.delete(entity);
        events.publish("ProductDeleteEvent", "product", deleted);
    }

    /**
     * The service died; the product record follows. Correlated by owner +
     * name (the modify path keeps them in step); with twin lines only the
     * first match closes — logged, not hidden.
     */
    @Transactional
    public void closeForTerminatedService(String tenantId, String ownerPartyId, String serviceName) {
        repository.findFirstByTenantIdAndOwnerPartyIdAndNameAndStatus(
                tenantId, ownerPartyId, serviceName, "active").ifPresent(entity -> {
            entity.setStatus("cancelled");
            entity.setTerminationDate(java.time.OffsetDateTime.now());
            ProductDto closed = mapper.toDto(repository.save(entity));
            events.publish("ProductStateChangeEvent", "product", closed);
        });
    }

    /**
     * Scoped tokens address only their own products; anything else is a 404,
     * not a 403, so foreign ids do not leak existence.
     */
    private void requireOwn(Product entity) {
        partyScope.scopedPartyId().ifPresent(own -> {
            if (!own.equals(entity.getOwnerPartyId())) {
                throw NotFoundException.forResource(RESOURCE, entity.getId());
            }
        });
    }

    /**
     * Owner of a machine- or staff-created product (order provisioning sends
     * the order's relatedParty): the party in the customer role, if any.
     */
    private String customerPartyIn(List<Map<String, Object>> relatedParty) {
        if (relatedParty == null) {
            return null;
        }
        return relatedParty.stream()
                .filter(p -> "customer".equalsIgnoreCase(String.valueOf(p.get("role"))))
                .map(p -> String.valueOf(p.get("id")))
                .findFirst()
                .orElse(null);
    }
}
