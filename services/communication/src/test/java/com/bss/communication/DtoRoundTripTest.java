package com.bss.communication;

import com.bss.communication.dto.EraseReceipt;
import com.bss.communication.dto.MarketingPreference;
import com.bss.communication.dto.MessagePatch;
import com.bss.communication.dto.MessageView;
import com.bss.communication.dto.NameValue;
import com.bss.communication.dto.PartyRef;
import com.bss.communication.dto.PrivacyExport;
import com.bss.communication.dto.RenderPreviewRequest;
import com.bss.communication.dto.SendRequest;
import com.bss.communication.dto.SuppressedSend;
import com.bss.communication.dto.TemplateRequest;
import com.bss.communication.dto.TemplateView;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure Jackson: the bytes and the key order of every communication wire record. */
class DtoRoundTripTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private String write(Object o) throws Exception {
        return mapper.writeValueAsString(o);
    }

    private static MessageView message(String source, String delivery, String sourceEventType) {
        return new MessageView("m-1", "/tmf-api/communicationManagement/v4/communicationMessage/m-1",
                "Your bill is ready", "Hi Paula", "inApp", "sent", source, delivery,
                List.of(PartyRef.customer("cust-a")),
                sourceEventType == null ? null : List.of(new NameValue("sourceEventType", sourceEventType)),
                OffsetDateTime.parse("2026-09-23T08:00:00Z"), OffsetDateTime.parse("2026-09-23T08:00:00Z"),
                "CommunicationMessage");
    }

    @Test
    void aMintedMessageCarriesItsSourceEventAndNothingItDoesNotHave() throws Exception {
        assertThat(write(message(null, null, "BillCreateEvent")))
                .isEqualTo("{\"id\":\"m-1\","
                        + "\"href\":\"/tmf-api/communicationManagement/v4/communicationMessage/m-1\","
                        + "\"subject\":\"Your bill is ready\",\"content\":\"Hi Paula\","
                        + "\"messageType\":\"inApp\",\"status\":\"sent\","
                        + "\"relatedParty\":[{\"id\":\"cust-a\",\"role\":\"customer\",\"@referredType\":\"Individual\"}],"
                        + "\"characteristic\":[{\"name\":\"sourceEventType\",\"value\":\"BillCreateEvent\"}],"
                        + "\"sendTime\":\"2026-09-23T08:00:00Z\",\"lastUpdate\":\"2026-09-23T08:00:00Z\","
                        + "\"@type\":\"CommunicationMessage\"}");
    }

    @Test
    void anAdHocSendCarriesSourceAndDeliveryStatusInTheirOwnSlots() throws Exception {
        assertThat(write(message("campaign", "sandbox-suppressed", null)))
                .contains("\"status\":\"sent\",\"source\":\"campaign\",\"deliveryStatus\":\"sandbox-suppressed\",\"relatedParty\"")
                .doesNotContain("characteristic");
    }

    @Test
    void anEmptyBodyStillWritesSubjectAndContentBecauseTheMapAlwaysDid() throws Exception {
        MessageView bare = new MessageView("m-2", "/h", null, null, "email", "sent", null, null,
                List.of(PartyRef.customer("prospect:a@b.example")), null, null, null, "CommunicationMessage");
        assertThat(write(bare)).isEqualTo("{\"id\":\"m-2\",\"href\":\"/h\",\"subject\":null,\"content\":null,"
                + "\"messageType\":\"email\",\"status\":\"sent\","
                + "\"relatedParty\":[{\"id\":\"prospect:a@b.example\",\"role\":\"customer\","
                + "\"@referredType\":\"Individual\"}],\"sendTime\":null,\"lastUpdate\":null,"
                + "\"@type\":\"CommunicationMessage\"}");
    }

    @Test
    void aSuppressedSendSaysWhichWallStoppedIt() throws Exception {
        assertThat(write(SuppressedSend.of(0, 3)))
                .isEqualTo("{\"status\":\"suppressed\",\"capped\":0,\"optedOut\":3,"
                        + "\"reason\":\"every recipient has opted out of marketing\"}");
        assertThat(write(SuppressedSend.of(2, 0)))
                .isEqualTo("{\"status\":\"capped\",\"capped\":2,\"optedOut\":0,"
                        + "\"reason\":\"frequency cap reached for all recipients in the window\"}");
        assertThat(write(SuppressedSend.of(2, 1)).contains("\"status\":\"capped\"")).isTrue();
    }

    @Test
    void aSendRequestFindsItsReceiverAndItsCategory() throws Exception {
        SendRequest r = mapper.readValue("{\"subject\":\"Hi {{party.firstName}}\",\"content\":\"body\","
                + "\"messageType\":\"email\",\"source\":\"campaign\","
                + "\"relatedParty\":[{\"id\":\"org-1\",\"role\":\"owner\"},"
                + "{\"id\":\"cust-a\",\"role\":\"Customer\",\"@referredType\":\"Individual\"}],"
                + "\"characteristic\":[{\"name\":\"category\",\"value\":\"Transactional\"}],"
                + "\"context\":{\"order.id\":\"o-1\"},\"unknown\":true}", SendRequest.class);
        assertThat(r.receiver()).isEqualTo("cust-a");
        assertThat(r.transactional()).isTrue();
        assertThat(r.context()).containsEntry("order.id", "o-1");
        assertThat(r.source()).isEqualTo("campaign");

        SendRequest marketing = mapper.readValue("{\"subject\":\"s\",\"toEmail\":\" a@b.example \"}",
                SendRequest.class);
        assertThat(marketing.receiver()).isNull();
        assertThat(marketing.transactional()).isFalse();
        assertThat(marketing.toEmail().trim()).isEqualTo("a@b.example");
        assertThat(mapper.readValue("{\"status\":\"read\"}", MessagePatch.class).status()).isEqualTo("read");
    }

    @Test
    void aTemplateAnswersTheAuthorsOwnLocalesDocument() throws Exception {
        TemplateView t = new TemplateView("t-1", "/h", "Welcome", "email",
                mapper.readTree("{\"en\":{\"subject\":\"Hi\",\"body\":\"Welcome {{party.firstName}}\"}}"),
                null, OffsetDateTime.parse("2026-09-23T08:00:00Z"), "MessageTemplate");
        assertThat(write(t)).isEqualTo("{\"id\":\"t-1\",\"href\":\"/h\",\"name\":\"Welcome\","
                + "\"channel\":\"email\",\"locales\":{\"en\":{\"subject\":\"Hi\","
                + "\"body\":\"Welcome {{party.firstName}}\"}},"
                + "\"lastUpdate\":\"2026-09-23T08:00:00Z\",\"@type\":\"MessageTemplate\"}");

        TemplateRequest req = mapper.readValue("{\"name\":\"W\",\"channel\":\"email\","
                + "\"locales\":{\"en\":{\"subject\":\"Hi\",\"body\":\"b\"}}}", TemplateRequest.class);
        assertThat(req.locales().isObject()).isTrue();
        assertThat(req.promotionRef()).isNull();                       // absent: leave the row alone
        TemplateRequest cleared = mapper.readValue("{\"promotionRef\":null}", TemplateRequest.class);
        assertThat(cleared.promotionRef().isNull()).isTrue();          // explicit null: clear it
        TemplateRequest asString = mapper.readValue("{\"locales\":\"{\\\"en\\\":{}}\"}", TemplateRequest.class);
        assertThat(asString.locales().textValue()).isEqualTo("{\"en\":{}}");
    }

    @Test
    void thePrivacyShelfAndThePreferenceKeepTheirKeyOrder() throws Exception {
        assertThat(write(new PrivacyExport("messages", 0, List.of())))
                .isEqualTo("{\"category\":\"messages\",\"count\":0,\"items\":[]}");
        assertThat(write(new EraseReceipt("messages", 4, 0)))
                .isEqualTo("{\"category\":\"messages\",\"deleted\":4,\"retained\":0}");
        assertThat(write(new MarketingPreference.View(true)))
                .isEqualTo("{\"marketingOptOut\":true}");
        assertThat(mapper.readValue("{\"optOut\":true}", MarketingPreference.Request.class).optedOut()).isTrue();
        assertThat(mapper.readValue("{\"optOut\":\"TRUE\"}", MarketingPreference.Request.class).optedOut()).isTrue();
        assertThat(mapper.readValue("{}", MarketingPreference.Request.class).optedOut()).isFalse();
        assertThat(mapper.readValue("{\"optOut\":false}", MarketingPreference.Request.class).optedOut()).isFalse();
        RenderPreviewRequest p = mapper.readValue("{\"locale\":\"nb\",\"context\":{\"a\":1}}",
                RenderPreviewRequest.class);
        assertThat(p.locale()).isEqualTo("nb");
        assertThat(p.context()).containsEntry("a", 1);
    }
}
