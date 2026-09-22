package com.bss.cart;

import com.bss.cart.dto.AcpFeedItem;
import com.bss.cart.dto.AcpMoney;
import com.bss.cart.dto.CartItem;
import com.bss.cart.dto.CartPatch;
import com.bss.cart.dto.CartRequest;
import com.bss.cart.dto.CartView;
import com.bss.cart.dto.CheckoutSessionRequest;
import com.bss.cart.dto.CheckoutSessionView;
import com.bss.cart.dto.CompleteRequest;
import com.bss.cart.dto.EntityRef;
import com.bss.cart.dto.EraseReceipt;
import com.bss.cart.dto.LineItem;
import com.bss.cart.dto.PartyRef;
import com.bss.cart.dto.PaymentRequest;
import com.bss.cart.dto.PrivacyExport;
import com.bss.cart.dto.ProductOrderRequest;
import com.bss.cart.entity.ShoppingCart;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wire records write the bytes the maps used to write: keys in the same
 * order, a channel's own line keys round-tripping untouched behind the
 * standard's, ACP money as the plain string it always was. Pure Jackson,
 * configured as Spring Boot configures it — no context, no database.
 */
class DtoRoundTripTest {

    private final ObjectMapper json = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static final OffsetDateTime T = OffsetDateTime.parse("2026-09-22T10:00:00Z");
    private static final TypeReference<List<CartItem>> LINES = new TypeReference<>() {
    };

    private String write(Object o) throws Exception {
        return json.writeValueAsString(o);
    }

    private static ShoppingCart cart(String owner) {
        ShoppingCart c = new ShoppingCart();
        c.setId("c1");
        c.setHref("/tmf-api/shoppingCart/v4/shoppingCart/c1");
        c.setStatus("active");
        c.setOwnerPartyId(owner);
        c.setCreatedAt(T);
        c.setLastUpdate(T);
        return c;
    }

    @Test
    void guestCart_writesNoPartyAndNoRelatedEntity() throws Exception {
        assertEquals("{\"id\":\"c1\",\"href\":\"/tmf-api/shoppingCart/v4/shoppingCart/c1\",\"status\":\"active\","
                + "\"cartItem\":[],\"creationDate\":\"2026-09-22T10:00:00Z\",\"lastUpdate\":\"2026-09-22T10:00:00Z\","
                + "\"@type\":\"ShoppingCart\"}", write(CartView.of(cart(null), List.of(), null)));
    }

    @Test
    void claimedCheckedOutCart_writesPartyThenLinesThenOrder() throws Exception {
        ShoppingCart c = cart("p1");
        c.setStatus("checkedOut");
        CartView v = CartView.of(c, List.of(CartItem.add(2, EntityRef.of("o1", "Kids Smartwatch"))),
                List.of(new EntityRef("ord-1", null, null, "ProductOrder", null)));
        assertEquals("{\"id\":\"c1\",\"href\":\"/tmf-api/shoppingCart/v4/shoppingCart/c1\",\"status\":\"checkedOut\","
                + "\"relatedParty\":[{\"id\":\"p1\",\"role\":\"customer\",\"@referredType\":\"Individual\"}],"
                + "\"cartItem\":[{\"action\":\"add\",\"quantity\":2,\"productOffering\":{\"id\":\"o1\",\"name\":\"Kids Smartwatch\"}}],"
                + "\"relatedEntity\":[{\"id\":\"ord-1\",\"@referredType\":\"ProductOrder\"}],"
                + "\"creationDate\":\"2026-09-22T10:00:00Z\",\"lastUpdate\":\"2026-09-22T10:00:00Z\",\"@type\":\"ShoppingCart\"}",
                write(v));
    }

    @Test
    void storefrontLine_keepsItsOwnKeys_behindTheStandards() throws Exception {
        String posted = "[{\"id\":\"o1##[]\",\"key\":\"o1##[]\",\"offeringId\":\"o1\",\"name\":\"Kids Smartwatch\","
                + "\"quantity\":2,\"selections\":[{\"offeringId\":\"o2\",\"characteristics\":{\"color\":\"Icy Blue\"}}],"
                + "\"deal\":{\"id\":\"d1\"},\"characteristics\":{\"color\":\"red\"}}]";
        List<CartItem> lines = json.readValue(posted, LINES);
        assertEquals(2, lines.get(0).quantity());
        assertEquals("o1", lines.get(0).extensions().get("offeringId"));
        // the standard's keys first (id, quantity), the channel's after, nothing lost
        assertEquals("[{\"id\":\"o1##[]\",\"quantity\":2,\"key\":\"o1##[]\",\"offeringId\":\"o1\",\"name\":\"Kids Smartwatch\","
                + "\"selections\":[{\"offeringId\":\"o2\",\"characteristics\":{\"color\":\"Icy Blue\"}}],"
                + "\"deal\":{\"id\":\"d1\"},\"characteristics\":{\"color\":\"red\"}}]", write(lines));
    }

    @Test
    void tmfLine_roundTripsByteIdentical_withAnOpenProductBlock() throws Exception {
        String posted = "[{\"id\":\"x\",\"action\":\"add\",\"quantity\":1,\"productOffering\":{\"id\":\"o2\",\"name\":\"TV\"},"
                + "\"product\":{\"productCharacteristic\":[{\"name\":\"color\",\"value\":\"blue\"}]},\"note\":[{\"text\":\"hi\"}]}]";
        assertEquals(posted, write(json.readValue(posted, LINES)));
    }

    @Test
    void cartPatch_tellsAbsentFromNull() throws Exception {
        assertNull(json.readValue("{\"status\":\"checkedOut\",\"foo\":1}", CartPatch.class).cartItem());
        CartPatch cleared = json.readValue("{\"cartItem\":null}", CartPatch.class);
        assertTrue(cleared.cartItem() != null && cleared.cartItem().isNull());
        CartPatch lines = json.readValue("{\"cartItem\":[{\"quantity\":3}]}", CartPatch.class);
        assertTrue(lines.cartItem().isArray());
        assertNull(json.readValue("{}", CartRequest.class).cartItem());
    }

    @Test
    void checkoutSession_writesLinesTotalsBuyerOrder() throws Exception {
        LineItem plain = LineItem.plain("li_1", new LineItem.Item("o1", "Kids Smartwatch"), 2,
                AcpMoney.of(new BigDecimal("79.99"), "EUR"), "oneTime", null,
                AcpMoney.of(new BigDecimal("159.98"), "EUR"));
        LineItem recurring = LineItem.plain("li_2", new LineItem.Item("o2", "TV"), 1,
                AcpMoney.of(new BigDecimal("14.99"), "EUR"), "recurring", "month", AcpMoney.of(BigDecimal.ZERO, "EUR"));
        CheckoutSessionView v = new CheckoutSessionView("acp_1", "completed", "EUR", List.of(plain, recurring),
                CheckoutSessionView.totalsOf(new BigDecimal("159.98"), "EUR"),
                json.readTree("{\"first_name\":\"Snap\"}"),
                new CheckoutSessionView.OrderRef("ord-1", "acp_1", "/tmf-api/productOrderingManagement/v4/productOrder/ord-1"));
        assertEquals("{\"id\":\"acp_1\",\"status\":\"completed\",\"currency\":\"EUR\",\"line_items\":["
                + "{\"id\":\"li_1\",\"item\":{\"id\":\"o1\",\"title\":\"Kids Smartwatch\"},\"quantity\":2,"
                + "\"unit_price\":{\"amount\":\"79.99\",\"currency\":\"EUR\"},\"price_type\":\"oneTime\","
                + "\"due_now\":{\"amount\":\"159.98\",\"currency\":\"EUR\"}},"
                + "{\"id\":\"li_2\",\"item\":{\"id\":\"o2\",\"title\":\"TV\"},\"quantity\":1,"
                + "\"unit_price\":{\"amount\":\"14.99\",\"currency\":\"EUR\"},\"price_type\":\"recurring\","
                + "\"recurring_period\":\"month\",\"due_now\":{\"amount\":\"0\",\"currency\":\"EUR\"}}],"
                + "\"totals\":[{\"type\":\"items_due_now\",\"amount\":\"159.98\",\"currency\":\"EUR\"},"
                + "{\"type\":\"total\",\"amount\":\"159.98\",\"currency\":\"EUR\"}],"
                + "\"buyer\":{\"first_name\":\"Snap\"},"
                + "\"order\":{\"id\":\"ord-1\",\"checkout_session_id\":\"acp_1\","
                + "\"permalink_url\":\"/tmf-api/productOrderingManagement/v4/productOrder/ord-1\"}}", write(v));
    }

    @Test
    void openSession_leavesBuyerAndOrderOff() throws Exception {
        CheckoutSessionView v = new CheckoutSessionView("acp_1", "ready_for_payment", "EUR", List.of(),
                CheckoutSessionView.totalsOf(BigDecimal.ZERO, "EUR"), null, null);
        assertEquals("{\"id\":\"acp_1\",\"status\":\"ready_for_payment\",\"currency\":\"EUR\",\"line_items\":[],"
                + "\"totals\":[{\"type\":\"items_due_now\",\"amount\":\"0\",\"currency\":\"EUR\"},"
                + "{\"type\":\"total\",\"amount\":\"0\",\"currency\":\"EUR\"}]}", write(v));
    }

    @Test
    void storedConfiguredLine_readsBack_withTheConfiguratorsDocumentsUntouched() throws Exception {
        // a row an older image wrote: item keys in Map.of's order, the configurator's echo verbatim
        String stored = "[{\"id\":\"li_1\",\"item\":{\"title\":\"Family Max\",\"id\":\"b1\"},\"quantity\":1,"
                + "\"unit_price\":{\"amount\":\"108.49\",\"currency\":\"EUR\"},\"price_type\":\"recurring\","
                + "\"recurring_period\":\"month\",\"due_now\":{\"amount\":\"49.0\",\"currency\":\"EUR\"},"
                + "\"configuration\":{\"productOffering\":{\"id\":\"b1\"},\"selectedOption\":[{\"id\":\"m1\",\"name\":\"Mobile\","
                + "\"characteristic\":[{\"value\":\"512GB\",\"name\":\"storage\"}]}],\"@type\":\"ProductConfiguration\"},"
                + "\"price_line\":[{\"name\":\"Base\",\"price\":{\"unit\":\"EUR\",\"value\":49.0}}]}]";
        List<LineItem> lines = json.readValue(stored, new TypeReference<List<LineItem>>() { });
        assertEquals(new BigDecimal("49.0"), lines.get(0).dueNow().decimal());
        assertEquals("Family Max", lines.get(0).item().title());
        assertEquals("[{\"id\":\"li_1\",\"item\":{\"id\":\"b1\",\"title\":\"Family Max\"},\"quantity\":1,"
                + "\"unit_price\":{\"amount\":\"108.49\",\"currency\":\"EUR\"},\"price_type\":\"recurring\","
                + "\"recurring_period\":\"month\",\"due_now\":{\"amount\":\"49.0\",\"currency\":\"EUR\"},"
                + "\"configuration\":{\"productOffering\":{\"id\":\"b1\"},\"selectedOption\":[{\"id\":\"m1\",\"name\":\"Mobile\","
                + "\"characteristic\":[{\"value\":\"512GB\",\"name\":\"storage\"}]}],\"@type\":\"ProductConfiguration\"},"
                + "\"price_line\":[{\"name\":\"Base\",\"price\":{\"unit\":\"EUR\",\"value\":49.0}}]}]", write(lines));
    }

    @Test
    void checkoutRequest_readsQuantityAsStringOrNumber_andKeepsThePicksAsATree() throws Exception {
        CheckoutSessionRequest r = json.readValue("{\"items\":[{\"id\":\"o1\",\"quantity\":\"2\"},"
                + "{\"id\":\"b1\",\"configuration\":{\"selectedOption\":[{\"id\":\"m1\"}]}},{\"quantity\":1}],"
                + "\"buyer\":{\"email\":\"a@b.c\"},\"stranger\":true}", CheckoutSessionRequest.class);
        assertEquals(3, r.items().size());
        assertEquals(2, r.items().get(0).quantity());
        assertNull(r.items().get(0).configuration());
        assertTrue(r.items().get(1).configuration().isObject());
        assertNull(r.items().get(2).id());
        assertEquals("a@b.c", r.buyer().path("email").asText());
        assertEquals("spt_1", json.readValue("{\"payment_data\":{\"token\":\"spt_1\",\"provider\":\"x\"}}",
                CompleteRequest.class).token());
        assertNull(json.readValue("{}", CompleteRequest.class).token());
    }

    @Test
    void feedItem_readsOnlyWhatPricingNeeds() throws Exception {
        AcpFeedItem.Feed feed = json.readValue("{\"products\":[{\"id\":\"o1\",\"title\":\"TV\",\"description\":\"d\","
                + "\"link\":\"/x\",\"availability\":\"in_stock\",\"price\":{\"amount\":\"14.99\",\"currency\":\"EUR\"},"
                + "\"price_type\":\"recurring\",\"recurring_period\":\"month\",\"is_bundle\":false}]}", AcpFeedItem.Feed.class);
        AcpFeedItem item = feed.products().get(0);
        assertEquals(new BigDecimal("14.99"), item.price().decimal());
        assertEquals("month", item.recurringPeriod());
    }

    @Test
    void paymentAndOrder_writeTheDownstreamShapes() throws Exception {
        PaymentRequest p = new PaymentRequest("Agentic checkout acp_1",
                new PaymentRequest.Money("EUR", new BigDecimal("159.98")),
                PaymentRequest.PaymentMethod.sharedPaymentToken("spt_1"), "uuid-1");
        assertEquals("{\"description\":\"Agentic checkout acp_1\",\"amount\":{\"unit\":\"EUR\",\"value\":159.98},"
                + "\"paymentMethod\":{\"@type\":\"sharedPaymentToken\",\"token\":\"spt_1\"},\"correlatorId\":\"uuid-1\"}",
                write(p));

        ProductOrderRequest.OrderItem parent = new ProductOrderRequest.OrderItem("1", "add", 1,
                EntityRef.of("b1", "Family Max", "ProductOffering"), null, null)
                .withChildren(List.of(new ProductOrderRequest.OrderItem("1.1", "add", 1,
                        EntityRef.of("m1", "Mobile", "ProductOffering"),
                        new ProductOrderRequest.Product(json.readTree("[{\"name\":\"storage\",\"value\":\"512GB\"}]")), null)),
                        null);
        ProductOrderRequest o = new ProductOrderRequest("Agentic checkout (ACP session acp_1)", "agenticCommerce",
                List.of(parent), List.of(PartyRef.customer("p1")), List.of(EntityRef.of("pay-1", null, "Payment")));
        assertEquals("{\"description\":\"Agentic checkout (ACP session acp_1)\",\"category\":\"agenticCommerce\","
                + "\"productOrderItem\":[{\"id\":\"1\",\"action\":\"add\",\"quantity\":1,"
                + "\"productOffering\":{\"id\":\"b1\",\"name\":\"Family Max\",\"@referredType\":\"ProductOffering\"},"
                + "\"productOrderItem\":[{\"id\":\"1.1\",\"action\":\"add\",\"quantity\":1,"
                + "\"productOffering\":{\"id\":\"m1\",\"name\":\"Mobile\",\"@referredType\":\"ProductOffering\"},"
                + "\"product\":{\"productCharacteristic\":[{\"name\":\"storage\",\"value\":\"512GB\"}]}}]}],"
                + "\"relatedParty\":[{\"id\":\"p1\",\"role\":\"customer\",\"@referredType\":\"Individual\"}],"
                + "\"payment\":[{\"id\":\"pay-1\",\"@referredType\":\"Payment\"}]}", write(o));
        ProductOrderRequest noPayment = new ProductOrderRequest("d", "agenticCommerce", List.of(),
                List.of(PartyRef.customer("p1")), null);
        assertTrue(!write(noPayment).contains("payment"));
    }

    @Test
    void privacyCorner_writesCountsInOrder() throws Exception {
        assertEquals("{\"category\":\"carts\",\"deleted\":2,\"retained\":0}", write(new EraseReceipt("carts", 2, 0)));
        String export = write(new PrivacyExport("carts", 1, List.of(cart("p1"))));
        assertTrue(export.startsWith("{\"category\":\"carts\",\"count\":1,\"items\":[{"));
        assertTrue(export.contains("\"ownerPartyId\":\"p1\""));
    }
}
