package com.bss.cart.service;

import com.bss.cart.api.ApiConstants;
import com.bss.cart.api.OffsetPageRequest;
import com.bss.cart.api.PagedResult;
import com.bss.cart.dto.CartItem;
import com.bss.cart.dto.CartPatch;
import com.bss.cart.dto.CartRequest;
import com.bss.cart.dto.CartView;
import com.bss.cart.dto.EntityRef;
import com.bss.cart.entity.ShoppingCart;
import com.bss.cart.events.DomainEventPublisher;
import com.bss.cart.exception.BadRequestException;
import com.bss.cart.exception.ConflictException;
import com.bss.cart.exception.NotFoundException;
import com.bss.cart.repository.ShoppingCartRepository;
import com.bss.cart.security.PartyScope;
import com.bss.cart.security.TenantContext;
import com.bss.cart.security.TenantScope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * TMF663: the cart is core-commerce state, shared by every channel. Access
 * model: an unowned (guest) cart is reachable by anyone holding its random id
 * — the id is the bearer secret, exactly like an unauthenticated basket token.
 * The first authenticated touch claims it for that party; from then on it is
 * party-scoped like every other resource (agents and back-office read across
 * parties for assisted checkout). Checked-out carts are immutable history.
 */
@Service
public class ShoppingCartService {

    private static final String RESOURCE = "ShoppingCart";
    private static final TypeReference<List<CartItem>> CART_ITEMS = new TypeReference<>() {
    };
    private static final TypeReference<List<EntityRef>> ENTITY_REFS = new TypeReference<>() {
    };

    private final ShoppingCartRepository repository;
    private final DomainEventPublisher events;
    private final PartyScope partyScope;
    private final TenantScope tenantScope;
    private final ObjectMapper objectMapper;
    private final com.bss.cart.tick.TickGuard tickGuard;
    private final long abandonMinutes;

    public ShoppingCartService(ShoppingCartRepository repository, DomainEventPublisher events,
            PartyScope partyScope, TenantScope tenantScope, ObjectMapper objectMapper,
            com.bss.cart.tick.TickGuard tickGuard,
            @Value("${bss.cart.abandon-minutes:1440}") long abandonMinutes) {
        this.repository = repository;
        this.events = events;
        this.partyScope = partyScope;
        this.tenantScope = tenantScope;
        this.objectMapper = objectMapper;
        this.tickGuard = tickGuard;
        this.abandonMinutes = abandonMinutes;
    }

    @Transactional
    public CartView create(CartRequest dto) {
        ShoppingCart entity = new ShoppingCart();
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        // Anonymous (guest) carts belong to the default tenant.
        entity.setTenantId(tenantScope.currentTenantId());
        entity.setHref(ApiConstants.BASE_PATH + "/shoppingCart/" + id);
        entity.setStatus(ShoppingCart.ACTIVE);
        entity.setOwnerPartyId(partyScope.scopedPartyId().orElse(null));
        entity.setCartItemJson(writeJson(dto == null ? null : dto.cartItem()));
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setLastUpdate(OffsetDateTime.now());
        CartView created = view(repository.save(entity));
        events.publish("ShoppingCartCreateEvent", "shoppingCart", created);
        return created;
    }

    @Transactional(readOnly = true)
    public CartView findById(String id) {
        ShoppingCart entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        requireAccess(entity);
        return view(entity);
    }

    /** Listing requires identity: customers see their carts, staff/agents filter by party. */
    @Transactional(readOnly = true)
    public PagedResult<CartView> findAll(int offset, int limit, Map<String, String> filters) {
        if (isAnonymous()) {
            throw new BadRequestException("listing carts requires identity; guests fetch by cart id");
        }
        ShoppingCart probe = new ShoppingCart();
        probe.setTenantId(tenantScope.currentTenantId());
        for (Map.Entry<String, String> f : filters.entrySet()) {
            switch (f.getKey()) {
                case "id" -> probe.setId(f.getValue());
                case "href" -> probe.setHref(f.getValue());
                case "status" -> probe.setStatus(f.getValue());
                case "relatedPartyId" -> probe.setOwnerPartyId(f.getValue());
                // TMF630 field-selection / sorting are not filters — ignore, don't reject.
                case "fields", "sort" -> { }
                default -> throw new BadRequestException("unsupported filter attribute '" + f.getKey() + "'");
            }
        }
        partyScope.scopedPartyId().ifPresent(probe::setOwnerPartyId);
        Page<ShoppingCart> page = repository.findAll(Example.of(probe), new OffsetPageRequest(offset, limit));
        return new PagedResult<>(page.getContent().stream().map(this::view).toList(), page.getTotalElements());
    }

    /**
     * The one mutation endpoint: replace items, claim for the calling party,
     * or transition to checkedOut (with the order ref). Active carts only.
     */
    @Transactional
    public CartView patch(String id, CartPatch patch) {
        // The claim-on-login lookup carries the tenant predicate too: a
        // customer can only claim a cart inside their own tenant.
        ShoppingCart entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        requireAccess(entity);
        if (!ShoppingCart.ACTIVE.equals(entity.getStatus())) {
            throw new ConflictException("cart is '" + entity.getStatus() + "' and immutable");
        }

        // An authenticated customer touching an unowned cart claims it.
        if (entity.getOwnerPartyId() == null) {
            partyScope.scopedPartyId().ifPresent(entity::setOwnerPartyId);
        }
        // absent leaves the lines alone; an explicit null clears them
        if (patch.cartItem() != null) {
            entity.setCartItemJson(patch.cartItem().isNull() ? null : writeJson(cartItems(patch.cartItem())));
        }
        if (patch.status() != null) {
            if (!ShoppingCart.CHECKED_OUT.equals(patch.status())) {
                throw new BadRequestException("the only supported transition is status: 'checkedOut'");
            }
            entity.setStatus(ShoppingCart.CHECKED_OUT);
            entity.setRelatedEntityJson(writeJson(patch.relatedEntity()));
        }
        entity.setLastUpdate(OffsetDateTime.now());
        CartView updated = view(repository.save(entity));
        events.publish(ShoppingCart.CHECKED_OUT.equals(entity.getStatus())
                ? "ShoppingCartCheckedOutEvent" : "ShoppingCartChangeEvent", "shoppingCart", updated);
        return updated;
    }

    /**
     * The martech trigger: owned carts idle past the threshold become
     * abandoned, once, with an event. Guest carts expire silently — there is
     * nobody to notify.
     */
    @Scheduled(fixedDelayString = "${bss.cart.sweep-interval-ms:60000}")
    @Transactional
    public int sweepAbandoned() {
        if (!tickGuard.claim("cart-sweep", java.time.Duration.ofSeconds(60))) {
            return 0; // another replica sweeps — one abandonment event per cart
        }
        try {
            return doSweepAbandoned();
        } finally {
            tickGuard.release("cart-sweep");
        }
    }

    private int doSweepAbandoned() {
        // System job, deliberately tenant-spanning: it sweeps every tenant's
        // idle carts in one pass. The SYSTEM context opens the row-level
        // security escape hatch; each event still carries its row's tenant.
        try (TenantContext ignored = TenantContext.actAsSystem()) {
            OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(abandonMinutes);
            List<ShoppingCart> idle = repository
                    .findByStatusAndOwnerPartyIdNotNullAndLastUpdateBefore(ShoppingCart.ACTIVE, cutoff);
            for (ShoppingCart cart : idle) {
                cart.setStatus(ShoppingCart.ABANDONED);
                cart.setLastUpdate(OffsetDateTime.now());
                events.publish("ShoppingCartAbandonedEvent", "shoppingCart", view(cart), cart.getTenantId());
            }
            repository.saveAll(idle);
            return idle.size();
        }
    }

    /**
     * Unowned cart: the id is the secret, anyone holding it may act (guest
     * flow). Owned cart: its party, or any authenticated non-customer
     * (agents doing assisted checkout, back-office). 404, never 403.
     */
    private void requireAccess(ShoppingCart entity) {
        if (entity.getOwnerPartyId() == null) {
            return;
        }
        if (isAnonymous()) {
            throw NotFoundException.forResource(RESOURCE, entity.getId());
        }
        partyScope.scopedPartyId().ifPresent(own -> {
            if (!own.equals(entity.getOwnerPartyId())) {
                throw NotFoundException.forResource(RESOURCE, entity.getId());
            }
        });
    }

    private boolean isAnonymous() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || auth instanceof AnonymousAuthenticationToken;
    }

    private CartView view(ShoppingCart entity) {
        return CartView.of(entity, readList(entity.getCartItemJson(), CART_ITEMS, List.of()),
                readList(entity.getRelatedEntityJson(), ENTITY_REFS, null));
    }

    /** The posted lines as a typed list — a body that is not a list of lines is refused, not stored. */
    private List<CartItem> cartItems(JsonNode node) {
        if (!node.isArray()) {
            throw new BadRequestException("cartItem must be a list of cart lines");
        }
        try {
            return objectMapper.convertValue(node, CART_ITEMS);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("cartItem is not a list of cart lines: " + e.getMessage());
        }
    }

    private String writeJson(Object value) {
        try {
            return value == null ? null : objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("unserializable JSON value", e);
        }
    }

    private <T> List<T> readList(String json, TypeReference<List<T>> type, List<T> whenAbsent) {
        try {
            return json == null ? whenAbsent : objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored JSON value is unreadable", e);
        }
    }
}
