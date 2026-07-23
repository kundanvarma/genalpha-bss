package com.bss.cart.service;

import com.bss.cart.client.AcpCommerceClients;
import com.bss.cart.entity.AcpSession;
import com.bss.cart.exception.BadRequestException;
import com.bss.cart.exception.ConflictException;
import com.bss.cart.exception.NotFoundException;
import com.bss.cart.repository.AcpSessionRepository;
import com.bss.cart.security.TenantScope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    private static final TypeReference<List<Map<String, Object>>> JSON_ARRAY = new TypeReference<>() {
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
    public Map<String, Object> create(Map<String, Object> request) {
        List<Map<String, Object>> items = requestedItems(request);
        List<Map<String, Object>> lines = priceLines(items);

        // The session rides a real TMF663 cart: agent baskets sit in the same
        // funnel (and the same abandonment analytics) as every human basket.
        Map<String, Object> cart = carts.create(Map.of("cartItem", lines.stream()
                .map(l -> Map.of(
                        "action", "add",
                        "quantity", l.get("quantity"),
                        "productOffering", Map.of(
                                "id", ((Map<?, ?>) l.get("item")).get("id"),
                                "name", ((Map<?, ?>) l.get("item")).get("title"))))
                .toList()));

        AcpSession session = new AcpSession();
        session.setId("acp_" + UUID.randomUUID());
        session.setTenantId(tenantScope.currentTenantId());
        session.setCartId(String.valueOf(cart.get("id")));
        session.setStatus(AcpSession.READY);
        session.setCurrency(currencyOf(lines));
        session.setLineItemJson(writeJson(lines));
        if (request.get("buyer") != null) {
            session.setBuyerJson(writeJson(request.get("buyer")));
        }
        session.setCreatedAt(OffsetDateTime.now());
        session.setLastUpdate(OffsetDateTime.now());
        return view(sessions.save(session));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        return view(find(id));
    }

    @Transactional
    public Map<String, Object> update(String id, Map<String, Object> request) {
        AcpSession session = find(id);
        requireOpen(session);
        if (request.get("items") != null) {
            List<Map<String, Object>> lines = priceLines(requestedItems(request));
            session.setLineItemJson(writeJson(lines));
            session.setCurrency(currencyOf(lines));
        }
        if (request.get("buyer") != null) {
            session.setBuyerJson(writeJson(request.get("buyer")));
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
    public Map<String, Object> complete(String id, Map<String, Object> request,
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
        List<Map<String, Object>> lines = readLines(session);
        if (lines.isEmpty()) {
            throw new BadRequestException("the session has no items");
        }
        BigDecimal dueNow = dueNowTotal(lines);

        String paymentId = null;
        if (dueNow.signum() > 0) {
            Object paymentData = request == null ? null : request.get("payment_data");
            Object token = paymentData instanceof Map<?, ?> pd ? pd.get("token") : null;
            if (token == null) {
                throw new BadRequestException("payment_data.token is required — the delegated "
                        + "payment token that authorizes exactly this cart");
            }
            Map<String, Object> payment = createPayment(session, dueNow, String.valueOf(token),
                    authorization);
            paymentId = String.valueOf(payment.get("id"));
        }

        Map<String, Object> order = createOrder(session, lines, paymentId, authorization);
        String orderId = String.valueOf(order.get("id"));

        // The cart retires exactly as a human checkout retires it.
        carts.patch(session.getCartId(), Map.of(
                "status", "checkedOut",
                "relatedEntity", List.of(Map.of(
                        "id", orderId, "name", "productOrder", "@referredType", "ProductOrder"))));

        session.setStatus(AcpSession.COMPLETED);
        session.setCompletedOrderId(orderId);
        session.setCompletedPaymentId(paymentId);
        session.setIdempotencyKey(idempotencyKey);
        session.setLastUpdate(OffsetDateTime.now());
        return view(sessions.save(session));
    }

    @Transactional
    public Map<String, Object> cancel(String id) {
        AcpSession session = find(id);
        if (AcpSession.COMPLETED.equals(session.getStatus())) {
            throw new ConflictException("a completed session cannot be canceled");
        }
        session.setStatus(AcpSession.CANCELED);
        session.setLastUpdate(OffsetDateTime.now());
        return view(sessions.save(session));
    }

    /* ---------- pricing ---------- */

    private List<Map<String, Object>> requestedItems(Map<String, Object> request) {
        Object raw = request == null ? null : request.get("items");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new BadRequestException("items is required: [{id, quantity}]");
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object entry : list) {
            if (entry instanceof Map<?, ?> m && m.get("id") != null) {
                int quantity = m.get("quantity") == null ? 1
                        : Integer.parseInt(String.valueOf(m.get("quantity")));
                if (quantity < 1) {
                    throw new BadRequestException("quantity must be at least 1");
                }
                items.add(Map.of("id", String.valueOf(m.get("id")), "quantity", quantity));
            }
        }
        if (items.isEmpty()) {
            throw new BadRequestException("items is required: [{id, quantity}]");
        }
        return items;
    }

    /** Resolve every requested offering against the tenant's own feed — the
     * agent buys at the price every other channel sees, or not at all. */
    private List<Map<String, Object>> priceLines(List<Map<String, Object>> items) {
        String tenantId = tenantScope.currentTenantId();
        List<Map<String, Object>> lines = new ArrayList<>();
        int n = 1;
        for (Map<String, Object> item : items) {
            String offeringId = String.valueOf(item.get("id"));
            Map<String, Object> feedItem = clients.feedItem(offeringId, tenantId);
            if (feedItem == null) {
                throw new BadRequestException("offering '" + offeringId
                        + "' is not available to agents (unknown, retired, or unpriced)");
            }
            int quantity = (int) item.get("quantity");
            Map<?, ?> price = (Map<?, ?>) feedItem.get("price");
            BigDecimal unit = new BigDecimal(String.valueOf(price.get("amount")));
            boolean oneTime = "oneTime".equals(feedItem.get("price_type"));
            BigDecimal lineDue = oneTime ? unit.multiply(BigDecimal.valueOf(quantity)) : BigDecimal.ZERO;

            Map<String, Object> line = new LinkedHashMap<>();
            line.put("id", "li_" + n++);
            line.put("item", Map.of(
                    "id", offeringId, "title", String.valueOf(feedItem.get("title"))));
            line.put("quantity", quantity);
            line.put("unit_price", Map.of(
                    "amount", unit.toPlainString(), "currency", price.get("currency")));
            line.put("price_type", feedItem.get("price_type"));
            if (feedItem.get("recurring_period") != null) {
                line.put("recurring_period", feedItem.get("recurring_period"));
            }
            // due now = one-time charges; recurring bills on the first invoice
            line.put("due_now", Map.of(
                    "amount", lineDue.toPlainString(), "currency", price.get("currency")));
            lines.add(line);
        }
        return lines;
    }

    private BigDecimal dueNowTotal(List<Map<String, Object>> lines) {
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> line : lines) {
            Map<?, ?> due = (Map<?, ?>) line.get("due_now");
            total = total.add(new BigDecimal(String.valueOf(due.get("amount"))));
        }
        return total;
    }

    private String currencyOf(List<Map<String, Object>> lines) {
        return lines.stream()
                .map(l -> String.valueOf(((Map<?, ?>) l.get("unit_price")).get("currency")))
                .findFirst().orElse(null);
    }

    /* ---------- downstream, as the caller ---------- */

    private Map<String, Object> createPayment(AcpSession session, BigDecimal dueNow,
            String paymentToken, String authorization) {
        try {
            return clients.createPayment(Map.of(
                    "description", "Agentic checkout " + session.getId(),
                    "amount", Map.of("unit", session.getCurrency(), "value", dueNow),
                    // The ACP delegated payment token IS the PSP-scoped token:
                    // it authorizes exactly this cart, this amount.
                    "paymentMethod", Map.of("@type", "sharedPaymentToken", "token", paymentToken),
                    // the payment correlator is the session's UUID (the column
                    // is 36 chars); one session, one authorization, ever
                    "correlatorId", session.getId().replaceFirst("^acp_", "")), authorization);
        } catch (RestClientResponseException e) {
            throw new ConflictException("payment was refused: " + e.getResponseBodyAsString());
        }
    }

    private Map<String, Object> createOrder(AcpSession session, List<Map<String, Object>> lines,
            String paymentId, String authorization) {
        List<Map<String, Object>> orderItems = new ArrayList<>();
        int n = 1;
        for (Map<String, Object> line : lines) {
            Map<?, ?> item = (Map<?, ?>) line.get("item");
            orderItems.add(Map.of(
                    "id", String.valueOf(n++),
                    "action", "add",
                    "quantity", line.get("quantity"),
                    "productOffering", Map.of(
                            "id", item.get("id"), "name", item.get("title"),
                            "@referredType", "ProductOffering")));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("description", "Agentic checkout (ACP session " + session.getId() + ")");
        // The channel marker: which AI bought what is answerable from the
        // order record itself, tenant-walled like everything else.
        body.put("category", "agenticCommerce");
        body.put("productOrderItem", orderItems);
        // The buyer is the delegated token's subject — the customer the
        // agent acts FOR, bound the same way assisted channels bind it.
        body.put("relatedParty", List.of(Map.of(
                "id", callerSubject(), "role", "customer", "@referredType", "Individual")));
        if (paymentId != null) {
            body.put("payment", List.of(Map.of("id", paymentId, "@referredType", "Payment")));
        }
        try {
            return clients.createOrder(body, authorization);
        } catch (RestClientResponseException e) {
            throw new ConflictException("the order was refused: " + e.getResponseBodyAsString());
        }
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

    private Map<String, Object> view(AcpSession session) {
        List<Map<String, Object>> lines = readLines(session);
        BigDecimal dueNow = dueNowTotal(lines);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", session.getId());
        view.put("status", session.getStatus());
        view.put("currency", session.getCurrency());
        view.put("line_items", lines);
        view.put("totals", List.of(
                Map.of("type", "items_due_now",
                        "amount", dueNow.toPlainString(), "currency", session.getCurrency()),
                Map.of("type", "total",
                        "amount", dueNow.toPlainString(), "currency", session.getCurrency())));
        if (session.getBuyerJson() != null) {
            view.put("buyer", readJson(session.getBuyerJson()));
        }
        if (session.getCompletedOrderId() != null) {
            view.put("order", Map.of(
                    "id", session.getCompletedOrderId(),
                    "checkout_session_id", session.getId(),
                    "permalink_url", "/tmf-api/productOrderingManagement/v4/productOrder/"
                            + session.getCompletedOrderId()));
        }
        return view;
    }

    private List<Map<String, Object>> readLines(AcpSession session) {
        try {
            return session.getLineItemJson() == null ? List.of()
                    : objectMapper.readValue(session.getLineItemJson(), JSON_ARRAY);
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

    private Object readJson(String json) {
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored JSON value is unreadable", e);
        }
    }
}
