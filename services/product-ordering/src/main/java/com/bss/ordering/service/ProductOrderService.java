package com.bss.ordering.service;

import com.bss.ordering.api.ApiConstants;
import com.bss.ordering.api.OffsetPageRequest;
import com.bss.ordering.api.PagedResult;
import com.bss.ordering.client.CatalogClient;
import com.bss.ordering.client.InventoryClient;
import com.bss.ordering.client.PartyClient;
import com.bss.ordering.client.StockClient;
import com.bss.ordering.dto.ProductOrderDto;
import com.bss.ordering.entity.ProductOrder;
import com.bss.ordering.events.DomainEventPublisher;
import com.bss.ordering.exception.NotFoundException;
import com.bss.ordering.exception.OrderValidationException;
import com.bss.ordering.mapper.ProductOrderMapper;
import com.bss.ordering.repository.ProductOrderRepository;
import com.bss.ordering.security.PartyScope;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private final PartyScope partyScope;
    private final StockClient stockClient;

    public ProductOrderService(ProductOrderRepository repository, ProductOrderMapper mapper,
            CatalogClient catalogClient, PartyClient partyClient, InventoryClient inventoryClient,
            DomainEventPublisher events, PartyScope partyScope, StockClient stockClient) {
        this.repository = repository;
        this.mapper = mapper;
        this.catalogClient = catalogClient;
        this.partyClient = partyClient;
        this.inventoryClient = inventoryClient;
        this.events = events;
        this.partyScope = partyScope;
        this.stockClient = stockClient;
    }

    @Transactional(readOnly = true)
    public PagedResult<ProductOrderDto> findAll(int offset, int limit, Map<String, String> filters) {
        ProductOrder probe = probeFor(filters);
        // Customers see their own orders only, whatever else they filter on.
        partyScope.scopedPartyId().ifPresent(probe::setOwnerPartyId);
        Page<ProductOrder> page = repository.findAll(Example.of(probe), new OffsetPageRequest(offset, limit));
        return new PagedResult<>(page.getContent().stream().map(mapper::toDto).toList(), page.getTotalElements());
    }

    /**
     * TMF630 attribute filtering: exact match on scalar attributes via
     * query-by-example. Unknown attributes are rejected rather than silently
     * matching everything.
     */
    private ProductOrder probeFor(Map<String, String> filters) {
        ProductOrder probe = new ProductOrder();
        for (Map.Entry<String, String> f : filters.entrySet()) {
            switch (f.getKey()) {
                case "id" -> probe.setId(f.getValue());
                case "state" -> probe.setState(f.getValue());
                case "category" -> probe.setCategory(f.getValue());
                case "productOfferingId" -> probe.setProductOfferingId(f.getValue());
                case "billingAccountId" -> probe.setBillingAccountId(f.getValue());
                case "orderDate" -> {
                    try {
                        probe.setOrderDate(OffsetDateTime.parse(f.getValue()));
                    } catch (DateTimeParseException e) {
                        throw new OrderValidationException("orderDate filter is not a valid date-time");
                    }
                }
                default -> throw new OrderValidationException("unsupported filter attribute '" + f.getKey() + "'");
            }
        }
        return probe;
    }

    @Transactional(readOnly = true)
    public ProductOrderDto findById(String id) {
        ProductOrder entity = repository.findById(id)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        requireOwn(entity);
        return mapper.toDto(entity);
    }

    @Transactional
    public ProductOrderDto create(ProductOrderDto dto) {
        partyScope.scopedPartyId().ifPresent(sub -> claimForParty(dto, sub));
        validateReferences(dto);
        if (dto.getState() == null || dto.getState().isBlank()) {
            dto.setState("acknowledged");
        }
        ProductOrder entity = mapper.toEntity(dto);
        entity.setOwnerPartyId(partyScope.scopedPartyId().orElseGet(() -> customerPartyIn(dto.getRelatedParty())));
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setHref(ApiConstants.BASE_PATH + "/productOrder/" + id);
        if (entity.getOrderDate() == null) {
            entity.setOrderDate(OffsetDateTime.now());
        }
        reserveStock(dto, id);
        ProductOrderDto created = mapper.toDto(repository.save(entity));
        events.publish("ProductOrderCreateEvent", "productOrder", created);
        return created;
    }

    /**
     * Every item naming an offering reserves stock — offerings without a
     * stock record are not stock-managed and pass through. One insufficient
     * item sinks the whole order: earlier reservations for it are released
     * (compensation) and the client gets a 400 naming the shortage.
     */
    private void reserveStock(ProductOrderDto dto, String orderId) {
        for (ItemRef item : flattenItems(dto.getProductOrderItem())) {
            StockClient.ReserveOutcome outcome =
                    stockClient.reserve(item.offeringId(), item.name(), item.quantity(), orderId);
            if (!outcome.ok()) {
                stockClient.release(orderId);
                throw new OrderValidationException(outcome.message());
            }
        }
    }

    private record ItemRef(String offeringId, String name, int quantity) {
    }

    private List<ItemRef> flattenItems(List<Map<String, Object>> items) {
        List<ItemRef> refs = new ArrayList<>();
        collectItems(items, refs);
        return refs;
    }

    @SuppressWarnings("unchecked")
    private void collectItems(List<Map<String, Object>> items, List<ItemRef> into) {
        if (items == null) {
            return;
        }
        for (Map<String, Object> item : items) {
            Object offering = item.get("productOffering");
            if (offering instanceof Map<?, ?> ref && ref.get("id") != null) {
                int quantity = item.get("quantity") instanceof Number n ? n.intValue() : 1;
                Object name = ref.get("name") != null ? ref.get("name") : ref.get("id");
                into.add(new ItemRef(String.valueOf(ref.get("id")), String.valueOf(name), quantity));
            }
            if (item.get("productOrderItem") instanceof List<?> children) {
                collectItems((List<Map<String, Object>>) children, into);
            }
        }
    }

    @Transactional
    public ProductOrderDto patch(String id, ProductOrderDto patch) {
        ProductOrder entity = repository.findById(id)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        requireOwn(entity);
        requireCancelOnlyWhenScoped(patch);
        if (TERMINAL_STATES.contains(entity.getState())) {
            throw new OrderValidationException(
                    "order '" + id + "' is in terminal state '" + entity.getState() + "' and cannot be changed");
        }
        validateReferences(patch);
        boolean stateChanged = patch.getState() != null && !patch.getState().equals(entity.getState());
        boolean completing = STATE_COMPLETED.equals(patch.getState());
        boolean cancelling = stateChanged && "cancelled".equals(patch.getState());
        mapper.applyPatch(patch, entity);
        if (completing) {
            provision(entity);
            stockClient.consume(entity.getId());
        }
        if (cancelling) {
            stockClient.release(entity.getId());
        }
        ProductOrderDto updated = mapper.toDto(repository.save(entity));
        events.publish(stateChanged ? "ProductOrderStateChangeEvent" : "ProductOrderAttributeValueChangeEvent",
                "productOrder", updated);
        return updated;
    }

    @Transactional
    public void delete(String id) {
        if (partyScope.scopedPartyId().isPresent()) {
            throw new OrderValidationException(
                    "customers cancel orders by patching state to 'cancelled'; deletion is a back-office operation");
        }
        ProductOrder entity = repository.findById(id)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        ProductOrderDto deleted = mapper.toDto(entity);
        stockClient.release(id);
        repository.deleteById(id);
        events.publish("ProductOrderDeleteEvent", "productOrder", deleted);
    }

    /**
     * Scoped tokens address only their own orders; anything else is a 404, not
     * a 403, so foreign ids do not leak existence.
     */
    private void requireOwn(ProductOrder entity) {
        partyScope.scopedPartyId().ifPresent(own -> {
            if (!own.equals(entity.getOwnerPartyId())) {
                throw NotFoundException.forResource(RESOURCE, entity.getId());
            }
        });
    }

    /** The only change a customer may make to an order is cancelling it. */
    private void requireCancelOnlyWhenScoped(ProductOrderDto patch) {
        if (partyScope.scopedPartyId().isEmpty()) {
            return;
        }
        boolean onlyCancel = "cancelled".equals(patch.getState())
                && patch.getDescription() == null && patch.getCategory() == null
                && patch.getProductOfferingId() == null && patch.getBillingAccountId() == null
                && patch.getProductOrderItem() == null && patch.getRelatedParty() == null;
        if (!onlyCancel) {
            throw new OrderValidationException("customers may only cancel an order (state: 'cancelled')");
        }
    }

    /** Orders placed through a customer channel always carry their owner as a related party. */
    private void claimForParty(ProductOrderDto dto, String partyId) {
        List<Map<String, Object>> parties =
                dto.getRelatedParty() == null ? new ArrayList<>() : new ArrayList<>(dto.getRelatedParty());
        parties.removeIf(p -> !partyId.equals(p.get("id")) && "customer".equals(p.get("role")));
        if (parties.stream().noneMatch(p -> partyId.equals(p.get("id")))) {
            parties.add(Map.of(
                    "id", partyId,
                    "role", "customer",
                    "@referredType", "Individual"));
        }
        dto.setRelatedParty(parties);
    }

    /** Owner of a staff-placed order: the related party in the customer role, if any. */
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
        Map<String, Object> offering = order.getProductOfferingId() != null
                ? Map.of("id", order.getProductOfferingId())
                : null;
        Map<String, Object> billingAccount = order.getBillingAccountId() != null
                ? Map.of("id", order.getBillingAccountId())
                : null;
        inventoryClient.createProduct(new InventoryClient.NewProduct(
                name, "active", offering, billingAccount,
                mapper.toDto(order).getRelatedParty()));
    }
}
