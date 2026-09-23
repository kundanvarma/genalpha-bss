package com.bss.cart.service;

import com.bss.cart.client.AcpCommerceClients;
import com.bss.cart.dto.AcpFeedItem;
import com.bss.cart.dto.AcpMoney;
import com.bss.cart.dto.CartItem;
import com.bss.cart.dto.CartPatch;
import com.bss.cart.dto.CartRequest;
import com.bss.cart.dto.CartView;
import com.bss.cart.dto.CheckoutSessionRequest;
import com.bss.cart.dto.CheckoutSessionRequest.RequestedItem;
import com.bss.cart.dto.CheckoutSessionView;
import com.bss.cart.dto.CheckoutSessionView.OrderRef;
import com.bss.cart.dto.CompleteRequest;
import com.bss.cart.dto.EntityRef;
import com.bss.cart.dto.LineItem;
import com.bss.cart.dto.PartyRef;
import com.bss.cart.dto.PaymentRequest;
import com.bss.cart.dto.ProductOrderRequest;
import com.bss.cart.dto.ProductOrderRequest.OrderItem;
import com.bss.cart.dto.ProductOrderRequest.Product;
import com.bss.cart.entity.AcpSession;
import com.bss.cart.exception.BadRequestException;
import com.bss.cart.exception.ConflictException;
import com.bss.cart.exception.NotFoundException;
import com.bss.cart.repository.AcpSessionRepository;
import com.bss.cart.security.TenantScope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;

/**
 * The Agentic Commerce Protocol checkout lifecycle, mapped onto the commerce
 * spine that already exists: a session IS a TMF663 cart underneath; complete
 * IS a TMF622 order with a TMF670-style payment in front of it — created
 * with the CALLER'S delegated token, never this service's own identity.
 *
 * Money honesty mirrors the storefront: one-time charges are due now and
 * charged against the agent's payment token; a recurring price is shown as
 * recurring and bills on the first invoice, telecom-style. The total the
 * agent approves is the total the token is charged.
 */
@Service
public class AcpCheckoutService {

    private static final TypeReference<List<LineItem>> LINES = new TypeReference<>() {
    };

    private final AcpSessionRepository sessions;
    private final ShoppingCartService carts;
    private final AcpCommerceClients clients;
    private final TenantScope tenantScope;
    private final ObjectMapper objectMapper;

    public AcpCheckoutService(AcpSessionRepository sessions, ShoppingCartService carts,
            AcpCommerceClients clients, TenantScope tenantScope, ObjectMapper objectMapper) {
        this.sessions = sessions;
        this.carts = carts;
        this.clients = clients;
        this.tenantScope = tenantScope;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public CheckoutSessionView create(CheckoutSessionRequest request) {
        List<RequestedItem> items = requestedItems(request);
        List<LineItem> lines = priceLines(items);

        // The session rides a real TMF663 cart: agent baskets sit in the same
        // funnel (and the same abandonment analytics) as every human basket.
        CartView cart = carts.create(new CartRequest(lines.stream()
                .map(l -> CartItem.add(l.quantity(), EntityRef.of(l.item().id(), l.item().title())))
                .toList()));

        AcpSession session = new AcpSession();
        session.setId("acp_" + UUID.randomUUID());
        session.setTenantId(tenantScope.currentTenantId());
        session.setCartId(cart.id());
        session.setStatus(AcpSession.READY);
        session.setCurrency(currencyOf(lines));
        session.setLineItemJson(writeJson(lines));
        if (present(request.buyer())) {
            session.setBuyerJson(writeJson(request.buyer()));
        }
        session.setCreatedAt(OffsetDateTime.now());
        session.setLastUpdate(OffsetDateTime.now());
        return view(sessions.save(session));
    }

    @Transactional(readOnly = true)
    public CheckoutSessionView get(String id) {
        return view(find(id));
    }

    @Transactional
    public CheckoutSessionView update(String id, CheckoutSessionRequest request) {
        AcpSession session = find(id);
        requireOpen(session);
        if (request.items() != null) {
            List<LineItem> lines = priceLines(requestedItems(request));
            session.setLineItemJson(writeJson(lines));
            session.setCurrency(currencyOf(lines));
        }
        if (present(request.buyer())) {
            session.setBuyerJson(writeJson(request.buyer()));
        }
        session.setLastUpdate(OffsetDateTime.now());
        return view(sessions.save(session));
    }

    /**
     * Complete: charge the delegated payment token for the due-now total,
     * place the TMF622 order, retire the cart — all under the caller's own
     * authority. Replay-safe: the same Idempotency-Key returns the same
     * order; a different key against a completed session is refused.
     */
    @Transactional
    public CheckoutSessionView complete(String id, CompleteRequest request,
            String idempotencyKey, String authorization) {
        AcpSession session = find(id);
        if (AcpSession.COMPLETED.equals(session.getStatus())) {
            if (idempotencyKey != null && idempotencyKey.equals(session.getIdempotencyKey())) {
                return view(session); // the replay gets the SAME order, not a second one
            }
            throw new ConflictException("session is already completed");
        }
        requireOpen(session);
        if (authorization == null || isAnonymous()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "completing a checkout needs the buyer's delegated token");
        }
        List<LineItem> lines = readLines(session);
        if (lines.isEmpty()) {
            throw new BadRequestException("the session has no items");
        }
        BigDecimal dueNow = dueNowTotal(lines);

        String paymentId = null;
        if (dueNow.signum() > 0) {
            String token = request.token();
            if (token == null) {
                throw new BadRequestException("payment_data.token is required — the delegated "
                        + "payment token that authorizes exactly this cart");
            }
            paymentId = createPayment(session, dueNow, token, authorization).path("id").asText(null);
        }

        String orderId = createOrder(session, lines, paymentId, authorization).path("id").asText(null);

        // The cart retires exactly as a human checkout retires it.
        carts.patch(session.getCartId(), new CartPatch(null, "checkedOut",
                List.of(EntityRef.of(orderId, "productOrder", "ProductOrder"))));

        session.setStatus(AcpSession.COMPLETED);
        session.setCompletedOrderId(orderId);
        session.setCompletedPaymentId(paymentId);
        session.setIdempotencyKey(idempotencyKey);
        session.setLastUpdate(OffsetDateTime.now());
        return view(sessions.save(session));
    }

    @Transactional
    public CheckoutSessionView cancel(String id) {
        AcpSession session = find(id);
        if (AcpSession.COMPLETED.equals(session.getStatus())) {
            throw new ConflictException("a completed session cannot be canceled");
        }
        session.setStatus(AcpSession.CANCELED);
        session.setLastUpdate(OffsetDateTime.now());
        return view(sessions.save(session));
    }

    /* ---------- pricing ---------- */

    private List<RequestedItem> requestedItems(CheckoutSessionRequest request) {
        List<RequestedItem> raw = request == null ? null : request.items();
        if (raw == null || raw.isEmpty()) {
            throw new BadRequestException("items is required: [{id, quantity}]");
        }
        List<RequestedItem> items = new ArrayList<>();
        for (RequestedItem entry : raw) {
            if (entry == null || entry.id() == null) {
                continue;
            }
            int quantity = entry.quantity() == null ? 1 : entry.quantity();
            if (quantity < 1) {
                throw new BadRequestException("quantity must be at least 1");
            }
            // a configured bundle: picks ride the item, TMF760-shaped
            JsonNode configuration = entry.configuration() != null && entry.configuration().isObject()
                    ? entry.configuration() : null;
            items.add(new RequestedItem(entry.id(), quantity, configuration));
        }
        if (items.isEmpty()) {
            throw new BadRequestException("items is required: [{id, quantity}]");
        }
        return items;
    }

    /** Resolve every requested offering against the tenant's own feed — the
     * agent buys at the price every other channel sees, or not at all. */
    private List<LineItem> priceLines(List<RequestedItem> items) {
        String tenantId = tenantScope.currentTenantId();
        List<LineItem> lines = new ArrayList<>();
        int n = 1;
        for (RequestedItem item : items) {
            if (item.configuration() != null) {
                lines.add(configuredLine(item, "li_" + n++, tenantId));
                continue;
            }
            AcpFeedItem feedItem = clients.feedItem(item.id(), tenantId);
            if (feedItem == null) {
                throw new BadRequestException("offering '" + item.id()
                        + "' is not available to agents (unknown, retired, or unpriced)");
            }
            int quantity = item.quantity();
            BigDecimal unit = feedItem.price().decimal();
            boolean oneTime = "oneTime".equals(feedItem.priceType());
            // due now = one-time charges; recurring bills on the first invoice
            BigDecimal lineDue = oneTime ? unit.multiply(BigDecimal.valueOf(quantity)) : BigDecimal.ZERO;
            String currency = feedItem.price().currency();
            lines.add(LineItem.plain("li_" + n++, new LineItem.Item(item.id(), feedItem.title()), quantity,
                    AcpMoney.of(unit, currency), feedItem.priceType(), feedItem.recurringPeriod(),
                    AcpMoney.of(lineDue, currency)));
        }
        return lines;
    }

    /**
     * A configured bundle is priced by the TMF760 configurator, never by
     * this service: the check endpoint validates the picks (both bounds,
     * allowed values, policy) and answers with the price AND an order-ready
     * configuration — the same authority every other channel gets. A
     * rejected configuration surfaces the configurator's own messages, so
     * the agent learns exactly what a human shopper would.
     */
    private LineItem configuredLine(RequestedItem item, String lineId, String tenantId) {
        ObjectNode config = item.configuration().deepCopy();
        config.set("productOffering", objectMapper.createObjectNode().put("id", item.id()));
        JsonNode checked;
        try {
            checked = clients.checkConfiguration(config, tenantId);
        } catch (RestClientResponseException e) {
            throw new BadRequestException("the configurator refused the request: "
                    + e.getResponseBodyAsString());
        }
        JsonNode result = checked == null ? null : checked.path("checkProductConfigurationItem").path(0);
        if (result == null || !result.isObject()) {
            throw new BadRequestException("the configurator returned no verdict");
        }
        // "accepted" is the configurator's verdict — the value TMF760 returns
        // here, and the one the storefront and the CSR desk read. This asked
        // for "approved", so an agent buying a CONFIGURED bundle was refused
        // every single time, with the configurator's own empty message as the
        // reason. No gate ran the suite that proves this path, so it sat broken.
        if (!"accepted".equals(result.path("state").asText())) {
            JsonNode messages = result.path("message");
            throw new BadRequestException("the configuration was rejected: "
                    + (messages.isArray() ? String.join("; ",
                            StreamSupport.stream(messages.spliterator(), false).map(JsonNode::asText).toList())
                            : messages.asText()));
        }
        JsonNode price = result.path("configurationPrice");
        JsonNode monthly = price.path("monthlyTotal");
        JsonNode oneTime = price.path("oneTimeTotal");
        JsonNode orderReady = result.path("productConfiguration");
        int quantity = item.quantity();
        String currency = monthly.path("unit").asText();
        BigDecimal dueNow = new BigDecimal(oneTime.path("value").asText())
                .multiply(BigDecimal.valueOf(quantity));

        // the recurring side of the configuration; one-time charges are due now
        return LineItem.configured(lineId,
                new LineItem.Item(item.id(), orderReady.path("productOffering").path("name").asText()),
                quantity, new AcpMoney(monthly.path("value").asText(), currency), AcpMoney.of(dueNow, currency),
                orderReady, price.path("priceLine"));
    }

    private BigDecimal dueNowTotal(List<LineItem> lines) {
        BigDecimal total = BigDecimal.ZERO;
        for (LineItem line : lines) {
            total = total.add(line.dueNow().decimal());
        }
        return total;
    }

    private String currencyOf(List<LineItem> lines) {
        return lines.stream().map(l -> l.unitPrice().currency()).findFirst().orElse(null);
    }

    /* ---------- downstream, as the caller ---------- */

    private JsonNode createPayment(AcpSession session, BigDecimal dueNow, String paymentToken,
            String authorization) {
        try {
            return clients.createPayment(new PaymentRequest(
                    "Agentic checkout " + session.getId(),
                    new PaymentRequest.Money(session.getCurrency(), dueNow),
                    PaymentRequest.PaymentMethod.sharedPaymentToken(paymentToken),
                    // the payment correlator is the session's UUID (the column
                    // is 36 chars); one session, one authorization, ever
                    session.getId().replaceFirst("^acp_", "")), authorization);
        } catch (RestClientResponseException e) {
            throw new ConflictException("payment was refused: " + e.getResponseBodyAsString());
        }
    }

    private JsonNode createOrder(AcpSession session, List<LineItem> lines, String paymentId,
            String authorization) {
        List<OrderItem> orderItems = new ArrayList<>();
        int n = 1;
        for (LineItem line : lines) {
            OrderItem orderItem = new OrderItem(String.valueOf(n), "add", line.quantity(),
                    EntityRef.of(line.item().id(), line.item().title(), "ProductOffering"), null, null);
            // a configured bundle: the configurator's order-ready echo becomes
            // nested TMF622 items — the same shape the storefront submits, so
            // ordering's cardinality gate sees a channel it already trusts
            if (line.configuration() != null && line.configuration().isObject()) {
                orderItem = withConfiguredChildren(orderItem, line.configuration(), String.valueOf(n),
                        line.quantity());
            }
            orderItems.add(orderItem);
            n++;
        }
        ProductOrderRequest body = new ProductOrderRequest(
                "Agentic checkout (ACP session " + session.getId() + ")",
                // The channel marker: which AI bought what is answerable from the
                // order record itself, tenant-walled like everything else.
                "agenticCommerce",
                orderItems,
                // The buyer is the delegated token's subject — the customer the
                // agent acts FOR, bound the same way assisted channels bind it.
                List.of(PartyRef.customer(callerSubject())),
                paymentId == null ? null : List.of(EntityRef.of(paymentId, null, "Payment")));
        try {
            return clients.createOrder(body, authorization);
        } catch (RestClientResponseException e) {
            throw new ConflictException("the order was refused: " + e.getResponseBodyAsString());
        }
    }

    /** The nested children of a configured bundle item, with each option's
     * own picks as product.productCharacteristic — billing rates the
     * configuration from exactly these, like every other channel's order. */
    private OrderItem withConfiguredChildren(OrderItem orderItem, JsonNode config, String parentId,
            int quantity) {
        JsonNode options = config.path("selectedOption");
        if (!options.isArray() || options.isEmpty()) {
            return orderItem;
        }
        List<OrderItem> children = new ArrayList<>();
        int j = 1;
        for (JsonNode option : options) {
            if (!option.isObject()) {
                continue;
            }
            JsonNode picks = option.path("characteristic");
            children.add(new OrderItem(parentId + "." + j++, "add", quantity,
                    EntityRef.of(option.path("id").asText(), option.path("name").asText(), "ProductOffering"),
                    picks.isArray() && !picks.isEmpty() ? new Product(picks) : null, null));
        }
        JsonNode own = config.path("configurationCharacteristic");
        return orderItem.withChildren(children, own.isArray() && !own.isEmpty() ? new Product(own) : null);
    }

    /* ---------- plumbing ---------- */

    private AcpSession find(String id) {
        return sessions.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("CheckoutSession", id));
    }

    private void requireOpen(AcpSession session) {
        if (!AcpSession.READY.equals(session.getStatus())) {
            throw new ConflictException("session is '" + session.getStatus() + "'");
        }
    }

    private boolean isAnonymous() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || auth instanceof AnonymousAuthenticationToken;
    }

    private String callerSubject() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : auth.getName();
    }

    /** A posted block counts as present when it is there and not JSON null. */
    private static boolean present(JsonNode node) {
        return node != null && !(node instanceof NullNode) && !node.isMissingNode();
    }

    private CheckoutSessionView view(AcpSession session) {
        List<LineItem> lines = readLines(session);
        BigDecimal dueNow = dueNowTotal(lines);
        return new CheckoutSessionView(session.getId(), session.getStatus(), session.getCurrency(), lines,
                CheckoutSessionView.totalsOf(dueNow, session.getCurrency()),
                session.getBuyerJson() == null ? null : readTree(session.getBuyerJson()),
                session.getCompletedOrderId() == null ? null : new OrderRef(
                        session.getCompletedOrderId(), session.getId(),
                        "/tmf-api/productOrderingManagement/v4/productOrder/" + session.getCompletedOrderId()));
    }

    private List<LineItem> readLines(AcpSession session) {
        try {
            return session.getLineItemJson() == null ? List.of()
                    : objectMapper.readValue(session.getLineItemJson(), LINES);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored line items are unreadable", e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("unserializable JSON value", e);
        }
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            return new TextNode(json); // degrade as the map read degraded: the raw text, never a 500
        }
    }
}
