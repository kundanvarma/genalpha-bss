package com.bss.catalog.service;

import com.bss.catalog.api.ApiConstants;
import com.bss.catalog.api.OffsetPageRequest;
import com.bss.catalog.api.PagedResult;
import com.bss.catalog.dto.ProductOfferingDto;
import com.bss.catalog.entity.ProductOffering;
import com.bss.catalog.events.DomainEventPublisher;
import com.bss.catalog.exception.BadRequestException;
import com.bss.catalog.exception.NotFoundException;
import com.bss.catalog.mapper.ProductOfferingMapper;
import com.bss.catalog.security.TenantScope;
import com.bss.catalog.repository.ProductOfferingRepository;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ProductOfferingService {

    private static final String RESOURCE = "ProductOffering";

    private final ProductOfferingRepository repository;
    private final ProductOfferingMapper mapper;
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;
    private final com.bss.catalog.pim.ProductContentSource content;
    private final LegacyFederation legacy;
    private final com.bss.catalog.security.TenantRegistry tenants;
    private final LifecyclePolicy lifecycle;

    private final Channels channels;
    private final LaunchGovernanceService governance;

    public ProductOfferingService(ProductOfferingRepository repository, ProductOfferingMapper mapper, DomainEventPublisher events, TenantScope tenantScope,
            com.bss.catalog.pim.ProductContentSource content, LegacyFederation legacy,
            com.bss.catalog.security.TenantRegistry tenants,
            LifecyclePolicy lifecycle,
            Channels channels, LaunchGovernanceService governance) {
        this.governance = governance;
        this.channels = channels;
        this.lifecycle = lifecycle;
        this.repository = repository;
        this.mapper = mapper;
        this.events = events;
        this.tenantScope = tenantScope;
        this.content = content;
        this.legacy = legacy;
        this.tenants = tenants;
    }

    @Transactional(readOnly = true)
    public PagedResult<ProductOfferingDto> findAll(int offset, int limit, Map<String, String> filters) {
        boolean staff = lifecycle.staffCaller();
        // the channel this request sells through: what the front end declared,
        // else the web shop for the shop-facing world; staff see every channel
        // unless they ask for one (a machine caller forwarding a customer's header)
        String channel = channels.requested().orElse(staff ? null : Channels.DEFAULT);
        if (!staff) {
            // L1 enforcement: a guest or customer NEVER sees the unlaunched
            // shelf, whatever their query says — the deep pass caught the
            // client-side-courtesy hole this line closes
            filters = new java.util.LinkedHashMap<>(filters);
            filters.putIfAbsent("lifecycleStatus", "Active");
        }
        Page<ProductOffering> page = repository.findAll(probeFor(filters), new OffsetPageRequest(offset, limit));
        List<ProductOfferingDto> items = new java.util.ArrayList<>(
                page.getContent().stream()
                        .filter(e -> staff ? (channel == null || lifecycle.sellableIn(e, channel)) : lifecycle.sellableIn(e, channel))
                        .map(mapper::toDto).map(this::withContent).toList());
        // THE OVERLAY SEAM: a tenant wrapping a legacy estate sees that
        // catalog federated in — read-through, legacy-prefixed, fail-soft.
        // First page only, so paging math stays honest.
        List<ProductOfferingDto> federated = offset == 0
                ? legacy.offeringsFor(tenants.byId(tenantScope.currentTenantId())) : List.of();
        for (ProductOfferingDto f : federated) {
            if (staff ? (channel == null || lifecycle.sellableDtoIn(f, channel)) : lifecycle.sellableDtoIn(f, channel)) {
                items.add(f);
            }
        }
        return new PagedResult<>(items, page.getTotalElements() + federated.size());
    }

    /**
     * The PIM seam: a tenant with an external product-content system gets its
     * imagery resolved from there; everyone else keeps what the catalog
     * stores (authored via the console + TMF667). Reads only — what's stored
     * never changes, so the external PIM can be unplugged without loss.
     */
    private ProductOfferingDto withContent(ProductOfferingDto dto) {
        List<Map<String, Object>> external = content.attachmentsFor(tenantScope.currentTenantId(), dto);
        if (external != null) {
            dto.setAttachment(external);
        }
        return dto;
    }

    /**
     * TMF630 attribute filtering: exact match on scalar attributes via
     * query-by-example. Unknown attributes are rejected rather than silently
     * matching everything.
     */
    private Example<ProductOffering> probeFor(Map<String, String> filters) {
        ProductOffering probe = new ProductOffering();
        probe.setTenantId(tenantScope.currentTenantId());
        for (Map.Entry<String, String> f : filters.entrySet()) {
            switch (f.getKey()) {
                case "id" -> probe.setId(f.getValue());
                case "name" -> probe.setName(f.getValue());
                case "lifecycleStatus" -> probe.setLifecycleStatus(f.getValue());
                case "version" -> probe.setVersion(f.getValue());
                case "isBundle" -> {
                    if (!"true".equals(f.getValue()) && !"false".equals(f.getValue())) {
                        throw new BadRequestException("isBundle filter must be 'true' or 'false'");
                    }
                    probe.setIsBundle(Boolean.valueOf(f.getValue()));
                }
                case "lastUpdate" -> {
                    try {
                        probe.setLastUpdate(OffsetDateTime.parse(f.getValue()));
                    } catch (DateTimeParseException e) {
                        throw new BadRequestException("lastUpdate filter is not a valid date-time");
                    }
                }
                default -> throw new BadRequestException("unsupported filter attribute '" + f.getKey() + "'");
            }
        }
        return Example.of(probe);
    }

    @Transactional(readOnly = true)
    public ProductOfferingDto findById(String id) {
        if (id.startsWith(LegacyFederation.PREFIX)) {
            // overlay: a legacy-prefixed id resolves through the federation —
            // so ordering's reference validation covers wrapped offerings too
            ProductOfferingDto dto = legacy.byId(tenants.byId(tenantScope.currentTenantId()), id);
            if (dto == null) {
                throw NotFoundException.forResource(RESOURCE, id);
            }
            return dto;
        }
        ProductOffering entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        boolean staffCaller = lifecycle.staffCaller();
        String channel = channels.requested().orElse(staffCaller ? null : Channels.DEFAULT);
        if ((!staffCaller && !lifecycle.sellableIn(entity, channel))
                || (staffCaller && channel != null && !lifecycle.sellableIn(entity, channel))) {
            // an unlaunched, out-of-window, or wrong-channel offering does not
            // exist for the shop-facing world — 404, not 403, no oracle
            throw NotFoundException.forResource(RESOURCE, id);
        }
        return withContent(mapper.toDto(entity));
    }

    @Transactional
    public ProductOfferingDto create(ProductOfferingDto dto) {
        Channels.requireKnown(dto.getChannel());
        if (lifecycle.governed()) {
            // L1 governed mode: creation is a DRAFT, launch is a decision
            dto.setLifecycleStatus("In design");
        } else if (dto.getLifecycleStatus() == null) {
            dto.setLifecycleStatus("Active");
        }
        governance.beforeCreate(dto); // with governance on, only an approver's write lands live
        ProductOffering entity = mapper.toEntity(dto);
        // fixture-stable ids: a caller MAY supply the id (the demo seeds pin
        // well-known ids the suites share — same doctrine as persona ids in
        // the realm files); absent one, we mint as always
        String id = dto.getId() != null && !dto.getId().isBlank()
                ? dto.getId() : UUID.randomUUID().toString();
        entity.setId(id);
        entity.setTenantId(tenantScope.currentTenantId());
        entity.setHref(ApiConstants.BASE_PATH + "/productOffering/" + id);
        entity.setLastUpdate(OffsetDateTime.now());
        ProductOffering saved = repository.save(entity);
        governance.afterCreate(saved);
        ProductOfferingDto created = mapper.toDto(saved);
        events.publish("ProductOfferingCreateEvent", "productOffering", created);
        return created;
    }

    @Transactional
    public ProductOfferingDto patch(String id, ProductOfferingDto patch) {
        Channels.requireKnown(patch.getChannel());
        ProductOffering entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        String before = entity.getLifecycleStatus();
        if (patch.getLifecycleStatus() != null) {
            lifecycle.requireLegalTransition(before, patch.getLifecycleStatus());
        }
        // launch governance: a move to Launched needs an approval (or an approver);
        // a substance change after approval voids it
        boolean launchingNow = governance.beforePatch(entity, patch);
        String hashBefore = governance.substanceHash(entity);
        mapper.applyPatch(patch, entity);
        entity.setLastUpdate(OffsetDateTime.now());
        governance.afterPatch(entity, hashBefore, launchingNow);
        ProductOfferingDto updated = mapper.toDto(repository.save(entity));
        if (patch.getLifecycleStatus() != null && !patch.getLifecycleStatus().equals(before)) {
            events.publish("ProductOfferingStateChangeEvent", "productOffering", updated);
        }
        events.publish("ProductOfferingAttributeValueChangeEvent", "productOffering", updated);
        return updated;
    }

    @Transactional
    public void delete(String id) {
        ProductOffering entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        ProductOfferingDto deleted = mapper.toDto(entity);
        repository.delete(entity);
        events.publish("ProductOfferingDeleteEvent", "productOffering", deleted);
    }
}
