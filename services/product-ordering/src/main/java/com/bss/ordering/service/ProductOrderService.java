package com.bss.ordering.service;

import com.bss.ordering.api.ApiConstants;
import com.bss.ordering.api.OffsetPageRequest;
import com.bss.ordering.api.PagedResult;
import com.bss.ordering.client.CatalogClient;
import com.bss.ordering.client.InventoryClient;
import com.bss.ordering.client.PartyClient;
import com.bss.ordering.dto.ProductOrderDto;
import com.bss.ordering.entity.ProductOrder;
import com.bss.ordering.events.DomainEventPublisher;
import com.bss.ordering.exception.NotFoundException;
import com.bss.ordering.exception.OrderValidationException;
import com.bss.ordering.mapper.ProductOrderMapper;
import com.bss.ordering.repository.ProductOrderRepository;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

@Service
public class ProductOrderService {

    private static final String RESOURCE = "ProductOrder";
    private static final String STATE_COMPLETED = "completed";
    private static final Set<String> TERMINAL_STATES = Set.of(STATE_COMPLETED, "cancelled");

    private final ProductOrderRepository repository;
    private final ProductOrderMapper mapper;
    private final CatalogClient catalogClient;
    private final PartyClient partyClient;
    private final InventoryClient inventoryClient;
    private final DomainEventPublisher events;

    public ProductOrderService(ProductOrderRepository repository, ProductOrderMapper mapper,
            CatalogClient catalogClient, PartyClient partyClient, InventoryClient inventoryClient,
            DomainEventPublisher events) {
        this.repository = repository;
        this.mapper = mapper;
        this.catalogClient = catalogClient;
        this.partyClient = partyClient;
        this.inventoryClient = inventoryClient;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public PagedResult<ProductOrderDto> findAll(int offset, int limit) {
        Page<ProductOrder> page = repository.findAll(new OffsetPageRequest(offset, limit));
        return new PagedResult<>(page.getContent().stream().map(mapper::toDto).toList(), page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public ProductOrderDto findById(String id) {
        ProductOrder entity = repository.findById(id)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        return mapper.toDto(entity);
    }

    @Transactional
    public ProductOrderDto create(ProductOrderDto dto) {
        validateReferences(dto);
        ProductOrder entity = mapper.toEntity(dto);
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setHref(ApiConstants.BASE_PATH + "/productOrder/" + id);
        if (entity.getOrderDate() == null) {
            entity.setOrderDate(OffsetDateTime.now());
        }
        ProductOrderDto created = mapper.toDto(repository.save(entity));
        events.publish("ProductOrderCreateEvent", "productOrder", created);
        return created;
    }

    @Transactional
    public ProductOrderDto patch(String id, ProductOrderDto patch) {
        ProductOrder entity = repository.findById(id)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        if (TERMINAL_STATES.contains(entity.getState())) {
            throw new OrderValidationException(
                    "order '" + id + "' is in terminal state '" + entity.getState() + "' and cannot be changed");
        }
        validateReferences(patch);
        boolean stateChanged = patch.getState() != null && !patch.getState().equals(entity.getState());
        boolean completing = STATE_COMPLETED.equals(patch.getState());
        mapper.applyPatch(patch, entity);
        if (completing) {
            provision(entity);
        }
        ProductOrderDto updated = mapper.toDto(repository.save(entity));
        events.publish(stateChanged ? "ProductOrderStateChangeEvent" : "ProductOrderAttributeValueChangeEvent",
                "productOrder", updated);
        return updated;
    }

    @Transactional
    public void delete(String id) {
        ProductOrder entity = repository.findById(id)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        ProductOrderDto deleted = mapper.toDto(entity);
        repository.deleteById(id);
        events.publish("ProductOrderDeleteEvent", "productOrder", deleted);
    }

    /**
     * Cross-service reference validation: an order may only point at a product
     * offering that exists in the catalog and a billing account that exists in
     * party-account. Absent references are allowed; dangling ones are not.
     */
    private void validateReferences(ProductOrderDto dto) {
        if (dto.getProductOfferingId() != null
                && catalogClient.findOffering(dto.getProductOfferingId()).isEmpty()) {
            throw new OrderValidationException(
                    "productOffering '" + dto.getProductOfferingId() + "' not found in catalog");
        }
        if (dto.getBillingAccountId() != null
                && !partyClient.billingAccountExists(dto.getBillingAccountId())) {
            throw new OrderValidationException(
                    "billingAccount '" + dto.getBillingAccountId() + "' not found");
        }
    }

    /**
     * Order completion provisions the ordered product into inventory. Runs
     * inside the order transaction: if inventory rejects the product, the
     * state change rolls back and the order stays in its previous state.
     */
    private void provision(ProductOrder order) {
        String name = order.getDescription() != null && !order.getDescription().isBlank()
                ? order.getDescription()
                : "productOrder " + order.getId();
        inventoryClient.createProduct(new InventoryClient.NewProduct(
                name, "active", order.getProductOfferingId(), order.getBillingAccountId()));
    }
}
