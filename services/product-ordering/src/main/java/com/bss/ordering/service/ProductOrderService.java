package com.bss.ordering.service;

import com.bss.ordering.api.ApiConstants;
import com.bss.ordering.api.OffsetPageRequest;
import com.bss.ordering.api.PagedResult;
import com.bss.ordering.client.AgreementClient;
import com.bss.ordering.client.CatalogClient;
import com.bss.ordering.client.InventoryClient;
import com.bss.ordering.client.PartyClient;
import com.bss.ordering.client.PaymentClient;
import com.bss.ordering.client.PolicyClient;
import com.bss.ordering.client.PromotionClient;
import com.bss.ordering.client.StockClient;
import com.bss.ordering.dto.ProductOrderDto;
import com.bss.ordering.entity.ProductOrder;
import com.bss.ordering.events.DomainEventPublisher;
import com.bss.ordering.exception.NotFoundException;
import com.bss.ordering.exception.OrderValidationException;
import com.bss.ordering.mapper.ProductOrderMapper;
import com.bss.ordering.repository.ProductOrderRepository;
import com.bss.ordering.security.PartyScope;
import com.bss.ordering.security.TenantScope;
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
    private final com.bss.ordering.security.VerifiedIdentity verifiedIdentity;
    private final AgreementClient agreementClient;
    private final PromotionClient promotionClient;
    private final PartyClient partyClient;
    private final InventoryClient inventoryClient;
    private final DomainEventPublisher events;
    private final PartyScope partyScope;
    private final TenantScope tenantScope;
    private final StockClient stockClient;
    private final PaymentClient paymentClient;
    private final PolicyClient policyClient;

    public ProductOrderService(ProductOrderRepository repository, ProductOrderMapper mapper,
            CatalogClient catalogClient, com.bss.ordering.security.VerifiedIdentity verifiedIdentity, AgreementClient agreementClient, PromotionClient promotionClient, PartyClient partyClient, InventoryClient inventoryClient,
            DomainEventPublisher events, PartyScope partyScope, TenantScope tenantScope,
            StockClient stockClient, PaymentClient paymentClient, PolicyClient policyClient) {
        this.repository = repository;
        this.mapper = mapper;
        this.catalogClient = catalogClient;
        this.verifiedIdentity = verifiedIdentity;
        this.agreementClient = agreementClient;
        this.promotionClient = promotionClient;
        this.partyClient = partyClient;
        this.inventoryClient = inventoryClient;
        this.events = events;
        this.partyScope = partyScope;
        this.tenantScope = tenantScope;
        this.stockClient = stockClient;
        this.paymentClient = paymentClient;
        this.policyClient = policyClient;
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
        probe.setTenantId(tenantScope.currentTenantId());
        for (Map.Entry<String, String> f : filters.entrySet()) {
            switch (f.getKey()) {
                case "id" -> probe.setId(f.getValue());
                case "state" -> probe.setState(f.getValue());
                case "category" -> probe.setCategory(f.getValue());
                case "productOfferingId" -> probe.setProductOfferingId(f.getValue());
                case "billingAccountId" -> probe.setBillingAccountId(f.getValue());
                case "relatedPartyId" -> probe.setOwnerPartyId(f.getValue());
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
        ProductOrder entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        requireOwn(entity);
        return mapper.toDto(entity);
    }

    @Transactional
    public ProductOrderDto create(ProductOrderDto dto) {
        if (dto.getPromotionCode() != null && !dto.getPromotionCode().isBlank()
                && !promotionClient.isValid(dto.getPromotionCode())) {
            throw new OrderValidationException(
                    "promotion code '" + dto.getPromotionCode() + "' is not valid");
        }
        partyScope.scopedPartyId().ifPresent(sub -> claimOrPayForHousehold(dto, sub));
        requireSameOrgForBusinessAdmin(dto);
        validateReferences(dto);
        requireVerifiedIdentityIfNeeded(dto);
        validateBundleComposition(dto);
        if (dto.getState() == null || dto.getState().isBlank()) {
            dto.setState("acknowledged");
        }
        ProductOrder entity = mapper.toEntity(dto);
        entity.setTenantId(tenantScope.currentTenantId());
        entity.setOwnerPartyId(partyScope.scopedPartyId().orElseGet(() -> customerPartyIn(dto.getRelatedParty())));
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setHref(ApiConstants.BASE_PATH + "/productOrder/" + id);
        if (entity.getOrderDate() == null) {
            entity.setOrderDate(OffsetDateTime.now());
        }
        enforcePolicy(dto, entity.getOwnerPartyId());
        boolean modifyOnly = validateModifyItems(dto, entity.getOwnerPartyId());
        validatePayments(dto, entity.getOwnerPartyId(), id);
        reserveStock(dto, id);
        ProductOrderDto created = mapper.toDto(repository.save(entity));
        events.publish("ProductOrderCreateEvent", "productOrder", created);
        if (modifyOnly) {
            // A plan change touches no network: same service, same number. It
            // completes in this transaction so the channel sees it instantly.
            return completeNow(entity);
        }
        return created;
    }

    /**
     * Plan change (TMF622 action=modify): the item names the installed product
     * it changes and the offering to move it to. Hard validation up front —
     * the product must exist, be active and belong to the ordering customer;
     * the target must be a real non-bundle offering different from the current
     * one; and an unexpired commitment on the current plan blocks the change.
     * Returns true when the order is modify-only.
     */
    @SuppressWarnings("unchecked")
    private boolean validateModifyItems(ProductOrderDto dto, String ownerPartyId) {
        List<Map<String, Object>> items = flattenItemMaps(dto.getProductOrderItem());
        List<Map<String, Object>> modifies = items.stream()
                .filter(i -> "modify".equalsIgnoreCase(String.valueOf(i.get("action")))).toList();
        if (modifies.isEmpty()) {
            return false;
        }
        for (Map<String, Object> item : modifies) {
            String productId = item.get("product") instanceof Map<?, ?> p && p.get("id") != null
                    ? String.valueOf(p.get("id")) : null;
            if (productId == null) {
                throw new OrderValidationException(
                        "a modify item must reference the product it changes (product.id)");
            }
            Map<String, Object> product = inventoryClient.getProduct(productId)
                    .orElseThrow(() -> new OrderValidationException(
                            "product '" + productId + "' not found"));
            if (!"active".equalsIgnoreCase(String.valueOf(product.get("status")))) {
                throw new OrderValidationException("product '" + productId + "' is not active");
            }
            String productOwner = customerPartyIn(
                    (List<Map<String, Object>>) product.get("relatedParty"));
            if (ownerPartyId == null || !ownerPartyId.equals(productOwner)) {
                throw new OrderValidationException(
                        "product '" + productId + "' does not belong to the ordering customer");
            }
            String newOfferingId = item.get("productOffering") instanceof Map<?, ?> o && o.get("id") != null
                    ? String.valueOf(o.get("id")) : null;
            if (newOfferingId == null) {
                throw new OrderValidationException("a modify item must name the new productOffering");
            }
            CatalogClient.OfferingRef target = catalogClient.findOffering(newOfferingId)
                    .orElseThrow(() -> new OrderValidationException(
                            "productOffering '" + newOfferingId + "' not found in catalog"));
            if (target.bundle()) {
                throw new OrderValidationException(
                        "changing a plan to a bundle is not supported — order the bundle separately");
            }
            String currentOfferingId = product.get("productOffering") instanceof Map<?, ?> cur
                    && cur.get("id") != null ? String.valueOf(cur.get("id")) : null;
            if (newOfferingId.equals(currentOfferingId)) {
                throw new OrderValidationException(
                        "'" + product.get("name") + "' is already on that plan");
            }
            requireNoActiveCommitment(ownerPartyId, currentOfferingId,
                    String.valueOf(product.get("name")));
        }
        return modifies.size() == items.size();
    }

    /**
     * TMF651 guard: while the current plan's commitment window is open, the
     * plan cannot be changed. The agreement client fails open — an unreachable
     * agreement component must not strand customers on their plan.
     */
    private void requireNoActiveCommitment(String ownerPartyId, String offeringId, String productName) {
        if (offeringId == null) {
            return;
        }
        for (Map<String, Object> agreement : agreementClient.activeAgreements(ownerPartyId)) {
            // TMF651 wire shape: agreementPeriod {startDateTime, endDateTime}
            Object endRaw = agreement.get("agreementPeriod") instanceof Map<?, ?> period
                    ? period.get("endDateTime") : agreement.get("periodEnd");
            OffsetDateTime end;
            try {
                end = endRaw == null ? null : OffsetDateTime.parse(String.valueOf(endRaw));
            } catch (DateTimeParseException e) {
                continue;
            }
            if (end == null || end.isBefore(OffsetDateTime.now())
                    || !(agreement.get("agreementItem") instanceof List<?> items)) {
                continue;
            }
            for (Object it : items) {
                if (it instanceof Map<?, ?> m && m.get("productOffering") instanceof Map<?, ?> off
                        && offeringId.equals(String.valueOf(off.get("id")))) {
                    throw new OrderValidationException("'" + productName
                            + "' is under a commitment until " + end.toLocalDate()
                            + " and cannot be changed yet");
                }
            }
        }
    }

    /**
     * Modify-only orders complete inline: provisioning swaps the installed
     * product's offering (billing rates the new plan from the next run) and a
     * commitment on the new plan is minted like any other completion.
     */
    private ProductOrderDto completeNow(ProductOrder entity) {
        entity.setState(STATE_COMPLETED);
        provision(entity);
        mintCommitments(entity);
        ProductOrderDto updated = mapper.toDto(repository.save(entity));
        events.publish("ProductOrderStateChangeEvent", "productOrder", updated);
        return updated;
    }

    /**
     * Regulated/postpaid offerings can demand a verified real-world identity.
     * If any ordered offering requires it and the buyer has not stepped up
     * (BankID/Vipps), the order is refused with a 403 the channel recognizes
     * as a step-up prompt — not a generic denial.
     */
    private void requireVerifiedIdentityIfNeeded(ProductOrderDto dto) {
        for (ItemRef item : flattenItems(dto.getProductOrderItem())) {
            boolean needed = catalogClient.findOffering(item.offeringId())
                    .map(CatalogClient.OfferingRef::requiresVerifiedIdentity).orElse(false);
            if (needed && !verifiedIdentity.isVerified()) {
                throw new com.bss.ordering.exception.VerifiedIdentityRequiredException(item.name());
            }
        }
    }

    /**
     * Enforce a bundle's configuration cardinality (TMF620 soft bundle): each
     * choice group in the bundle must have between its lower and upper limit of
     * options selected — "pick 2 of 4". The customer's picks arrive as the
     * bundle item's nested productOrderItem children. Mandatory fixed components
     * are implicit (provisioned when the bundle decomposes) and not counted here.
     */
    @SuppressWarnings("unchecked")
    private void validateBundleComposition(ProductOrderDto dto) {
        if (dto.getProductOrderItem() == null) {
            return;
        }
        for (Map<String, Object> item : dto.getProductOrderItem()) {
            if (!(item.get("productOffering") instanceof Map<?, ?> off) || off.get("id") == null) {
                continue;
            }
            CatalogClient.OfferingRef bundle = catalogClient.findOffering(String.valueOf(off.get("id"))).orElse(null);
            if (bundle == null || !bundle.bundle() || bundle.choiceGroups().isEmpty()) {
                continue;
            }
            Set<String> selected = new java.util.HashSet<>();
            if (item.get("productOrderItem") instanceof List<?> children) {
                for (Object child : children) {
                    if (child instanceof Map<?, ?> cm && cm.get("productOffering") instanceof Map<?, ?> po
                            && po.get("id") != null) {
                        selected.add(String.valueOf(po.get("id")));
                    }
                }
            }
            for (Map<String, Object> group : bundle.choiceGroups()) {
                int lower = intOf(group.get("numberRelOfferLowerLimit"), 1);
                int upper = intOf(group.get("numberRelOfferUpperLimit"), 1);
                int chosen = 0;
                for (Object option : (List<Object>) group.get("options")) {
                    if (option instanceof Map<?, ?> om && om.get("id") != null
                            && selected.contains(String.valueOf(om.get("id")))) {
                        chosen++;
                    }
                }
                if (chosen < lower || chosen > upper) {
                    String groupName = String.valueOf(group.getOrDefault("name", "choice"));
                    String need = lower == upper ? "exactly " + lower : "between " + lower + " and " + upper;
                    throw new OrderValidationException("bundle '" + bundle.name() + "': '" + groupName
                            + "' requires " + need + " selection(s), but " + chosen + " were made");
                }
            }
        }
    }

    private static int intOf(Object o, int def) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return o == null ? def : Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /**
     * Ask the policy component whether a data-authored business rule forbids
     * this order (quantity caps, incompatibilities, eligibility). Rules are
     * configuration, not code — this is how an operator adds "max 2 SIMs per
     * order" without a redeploy. A definitive DENY becomes a 422; an
     * unreachable policy service fails open (see RestPolicyClient).
     */
    private void enforcePolicy(ProductOrderDto dto, String ownerPartyId) {
        PolicyClient.Decision decision = policyClient.evaluateOrder(buildPolicyContext(dto, ownerPartyId));
        if (!decision.allowed()) {
            throw new com.bss.ordering.exception.PolicyDeniedException(decision.message());
        }
    }

    /**
     * The request context a rule evaluates against — everything derivable from
     * the order itself (no extra calls), so evaluation is fast and deterministic:
     * the offerings ordered, quantity per offering, the largest single-offering
     * quantity, total units, and whether the buyer has a verified identity.
     */
    private Map<String, Object> buildPolicyContext(ProductOrderDto dto, String ownerPartyId) {
        List<ItemRef> items = flattenItems(dto.getProductOrderItem());
        Map<String, Integer> quantityByOffering = new java.util.LinkedHashMap<>();
        List<Map<String, Object>> itemList = new ArrayList<>();
        int total = 0;
        for (ItemRef item : items) {
            quantityByOffering.merge(item.offeringId(), item.quantity(), Integer::sum);
            total += item.quantity();
            itemList.add(Map.of("offeringId", item.offeringId(), "name", item.name(),
                    "quantity", item.quantity()));
        }
        int maxLineQuantity = quantityByOffering.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        Map<String, Object> context = new java.util.LinkedHashMap<>();
        context.put("party", ownerPartyId);
        context.put("verifiedIdentity", verifiedIdentity.isVerified());
        context.put("items", itemList);
        context.put("offeringIds", new ArrayList<>(quantityByOffering.keySet()));
        context.put("quantityByOffering", quantityByOffering);
        context.put("maxLineQuantity", maxLineQuantity);
        context.put("totalQuantity", total);
        context.put("lineCount", items.size());
        return context;
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
        ProductOrder entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        requireOwn(entity);
        requireCancelOnlyWhenScoped(patch);
        if (TERMINAL_STATES.contains(entity.getState())) {
            // Same-state completion is a no-op, not an error: staff and the
            // SOM may both drive completion and must never conflict.
            if (patch.getState() != null && patch.getState().equals(entity.getState())) {
                return mapper.toDto(entity);
            }
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
            paymentRefIds(entity).forEach(paymentClient::capture);
            mintCommitments(entity);
            if (entity.getPromoCode() != null && entity.getOwnerPartyId() != null) {
                promotionClient.redeem(entity.getPromoCode(), entity.getOwnerPartyId());
            }
        }
        if (cancelling) {
            stockClient.release(entity.getId());
            paymentRefIds(entity).forEach(paymentClient::voidPayment);
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
        ProductOrder entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        ProductOrderDto deleted = mapper.toDto(entity);
        stockClient.release(id);
        repository.delete(entity);
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
                && patch.getProductOrderItem() == null && patch.getRelatedParty() == null
                && patch.getPayment() == null;
        if (!onlyCancel) {
            throw new OrderValidationException("customers may only cancel an order (state: 'cancelled')");
        }
    }

    /**
     * Every payment ref on the order must be an authorized payment belonging
     * to the order's owner; one bad ref sinks the order before anything is
     * reserved or saved.
     */
    private void validatePayments(ProductOrderDto dto, String ownerPartyId, String orderId) {
        if (dto.getPayment() == null) {
            return;
        }
        for (Map<String, Object> ref : dto.getPayment()) {
            Object paymentId = ref.get("id");
            if (paymentId == null) {
                throw new OrderValidationException("payment reference without id");
            }
            String problem = paymentClient.validateAuthorized(String.valueOf(paymentId), ownerPartyId, orderId);
            if (!problem.isEmpty()) {
                throw new OrderValidationException(problem);
            }
        }
    }

    private List<String> paymentRefIds(ProductOrder entity) {
        List<Map<String, Object>> refs = mapper.toDto(entity).getPayment();
        return refs == null ? List.of()
                : refs.stream().map(r -> String.valueOf(r.get("id"))).toList();
    }

    /**
     * HOUSEHOLD BILLING: a person may order FOR someone whose ACTIVE payer
     * they are (parent orders the child's plan) — the dependent stays the
     * customer, the caller rides the order as its payer, and billing puts it
     * on the payer's bill exactly like a company order. Anyone else naming a
     * different customer is claimed back to themselves, as always. The link
     * is looked up live from the party source, never trusted from the request.
     */
    private void claimOrPayForHousehold(ProductOrderDto dto, String callerId) {
        String customer = customerPartyIn(dto.getRelatedParty());
        if (customer != null && !customer.equals(callerId)
                && callerId.equals(partyClient.householdPayerOf(customer).orElse(null))) {
            List<Map<String, Object>> parties = new ArrayList<>(dto.getRelatedParty());
            boolean stamped = parties.stream().anyMatch(p -> "payer".equals(p.get("role")));
            if (!stamped) {
                parties.add(Map.of("id", callerId, "role", "payer",
                        "@referredType", "Individual"));
                dto.setRelatedParty(parties);
            }
            return;
        }
        claimForParty(dto, callerId);
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
     * Order completion provisions the ordered products into inventory — one
     * product per item unit (an item with quantity 2 becomes two products,
     * matching the two devices that shipped), each carrying its offering ref
     * and configured characteristics so billing can rate it. Items without an
     * offering fall back to a single order-level product. Runs inside the
     * order transaction: if inventory rejects, the state change rolls back.
     */
    @SuppressWarnings("unchecked")
    /**
     * TMF651 seam: offerings that declare a commitment term become an active
     * agreement for the order's owner the moment the order completes.
     */
    private void mintCommitments(ProductOrder order) {
        if (order.getOwnerPartyId() == null) {
            return;
        }
        for (ItemRef item : flattenItems(mapper.toDto(order).getProductOrderItem())) {
            int months = catalogClient.findOffering(item.offeringId())
                    .map(CatalogClient.OfferingRef::commitmentMonths).orElse(0);
            if (months > 0) {
                agreementClient.activate(
                        item.name() + " — " + months + "-month commitment",
                        order.getOwnerPartyId(),
                        List.of(Map.of("productOffering",
                                Map.of("id", item.offeringId(), "name", item.name()))),
                        months);
            }
        }
    }

    private void provision(ProductOrder order) {
        ProductOrderDto dto = mapper.toDto(order);
        Map<String, Object> billingAccount = order.getBillingAccountId() != null
                ? Map.of("id", order.getBillingAccountId())
                : null;

        boolean provisioned = false;
        for (Map<String, Object> item : flattenItemMaps(dto.getProductOrderItem())) {
            if (!(item.get("productOffering") instanceof Map<?, ?> offering) || offering.get("id") == null) {
                continue;
            }
            if ("modify".equalsIgnoreCase(String.valueOf(item.get("action")))) {
                // Plan change: the existing product swaps its offering in
                // place — billing rates the new plan from the next run.
                String productId = String.valueOf(((Map<String, Object>) item.get("product")).get("id"));
                String newName = String.valueOf(offering.get("name") != null
                        ? offering.get("name") : offering.get("id"));
                inventoryClient.updateProduct(productId, Map.of(
                        "name", newName,
                        "productOffering", offering));
                provisioned = true;
                continue;
            }
            int quantity = item.get("quantity") instanceof Number n ? n.intValue() : 1;
            List<Map<String, Object>> characteristics = item.get("product") instanceof Map<?, ?> product
                    && product.get("productCharacteristic") instanceof List<?> chars
                    ? (List<Map<String, Object>>) chars
                    : null;
            String name = String.valueOf(offering.get("name") != null ? offering.get("name") : offering.get("id"));
            for (int unit = 0; unit < quantity; unit++) {
                inventoryClient.createProduct(new InventoryClient.NewProduct(
                        name, "active", (Map<String, Object>) offering, billingAccount,
                        dto.getRelatedParty(), characteristics));
                provisioned = true;
            }
        }
        if (!provisioned) {
            String name = order.getDescription() != null && !order.getDescription().isBlank()
                    ? order.getDescription()
                    : "productOrder " + order.getId();
            Map<String, Object> offering = order.getProductOfferingId() != null
                    ? Map.of("id", order.getProductOfferingId())
                    : null;
            inventoryClient.createProduct(new InventoryClient.NewProduct(
                    name, "active", offering, billingAccount, dto.getRelatedParty(), null));
        }
    }

    private List<Map<String, Object>> flattenItemMaps(List<Map<String, Object>> items) {
        List<Map<String, Object>> flat = new ArrayList<>();
        collectItemMaps(items, flat);
        return flat;
    }

    @SuppressWarnings("unchecked")
    private void collectItemMaps(List<Map<String, Object>> items, List<Map<String, Object>> into) {
        if (items == null) {
            return;
        }
        for (Map<String, Object> item : items) {
            into.add(item);
            if (item.get("productOrderItem") instanceof List<?> children) {
                collectItemMaps((List<Map<String, Object>>) children, into);
            }
        }
    }

    /**
     * B2B boundary: a business:admin token orders on behalf of people, but only
     * people inside the admin's OWN organization — both looked up live from the
     * party service, never trusted from the request.
     */
    private void requireSameOrgForBusinessAdmin(ProductOrderDto dto) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null || auth.getAuthorities().stream()
                .noneMatch(a -> "business:admin".equals(a.getAuthority()))) {
            return;
        }
        String adminOrg = partyClient.organizationOf(auth.getName())
                .orElseThrow(() -> new OrderValidationException(
                        "business admin has no organization membership"));
        String customer = customerPartyIn(dto.getRelatedParty());
        if (customer == null) {
            throw new OrderValidationException("order needs a customer relatedParty");
        }
        String memberOrg = partyClient.organizationOf(customer).orElse(null);
        if (!adminOrg.equals(memberOrg)) {
            throw new OrderValidationException(
                    "customer '" + customer + "' is not a member of your organization");
        }
        // SPLIT BILLING: the payer follows the ORDERER. A company admin's
        // order is company-paid — the org rides the order (and every product
        // provisioned from it) as the 'payer' related party. A member's own
        // purchase carries no payer and bills personally.
        List<Map<String, Object>> parties = new ArrayList<>(dto.getRelatedParty());
        boolean stamped = parties.stream().anyMatch(p -> "payer".equals(p.get("role")));
        if (!stamped) {
            parties.add(Map.of("id", adminOrg, "role", "payer",
                    "@referredType", "Organization"));
            dto.setRelatedParty(parties);
        }
    }
}
