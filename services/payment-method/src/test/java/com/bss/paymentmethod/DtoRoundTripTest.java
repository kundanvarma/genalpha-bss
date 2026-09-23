package com.bss.paymentmethod;

import com.bss.paymentmethod.dto.CardDetails;
import com.bss.paymentmethod.dto.PartyRef;
import com.bss.paymentmethod.dto.PaymentMethodRequest;
import com.bss.paymentmethod.dto.PaymentMethodView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure Jackson: the bytes and the key order of the saved-methods vault. */
class DtoRoundTripTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private String write(Object o) throws Exception {
        return mapper.writeValueAsString(o);
    }

    private static PaymentMethodView view(CardDetails details, boolean preferred) {
        return new PaymentMethodView("m-1", "/tmf-api/paymentMethods/v4/paymentMethod/m-1",
                "bankCard", "active", preferred, details,
                List.of(PartyRef.customer("party-1")));
    }

    @Test
    void aSavedCardShowsFourFactsAndTheTokenOnlyToTheMachine() throws Exception {
        assertThat(write(view(CardDetails.presentation("visa", "4242", "12/30"), true)))
                .isEqualTo("{\"id\":\"m-1\","
                        + "\"href\":\"/tmf-api/paymentMethods/v4/paymentMethod/m-1\","
                        + "\"@type\":\"bankCard\",\"status\":\"active\",\"preferred\":true,"
                        + "\"details\":{\"brand\":\"visa\",\"lastFourDigits\":\"4242\","
                        + "\"expiry\":\"12/30\"},"
                        + "\"relatedParty\":[{\"id\":\"party-1\",\"role\":\"customer\"}]}");
        assertThat(write(view(CardDetails.presentation("visa", "4242", "12/30")
                .withToken("tok_abc"), false)))
                .contains("\"expiry\":\"12/30\",\"token\":\"tok_abc\"}");
    }

    @Test
    void aHalfKnownCardLeavesOffWhatItDoesNotKnow() throws Exception {
        assertThat(write(view(CardDetails.presentation("mastercard", null, null)
                .withToken("tok_abc"), false)))
                .contains("\"details\":{\"brand\":\"mastercard\",\"token\":\"tok_abc\"},");
        assertThat(write(CardDetails.presentation(null, null, null))).isEqualTo("{}");
    }

    @Test
    void onlyARealJsonTrueMakesAMethodPreferred() throws Exception {
        assertThat(mapper.readValue("{\"preferred\":true}", PaymentMethodRequest.class)
                .preferredOrFalse()).isTrue();
        // the map path compared Boolean.TRUE against the parsed value
        assertThat(mapper.readValue("{\"preferred\":\"true\"}", PaymentMethodRequest.class)
                .preferredOrFalse()).isFalse();
        assertThat(mapper.readValue("{}", PaymentMethodRequest.class).preferredOrFalse())
                .isFalse();
    }

    @Test
    void theOwnerIsTheFirstPartyThatActuallyNamesOne() throws Exception {
        assertThat(mapper.readValue("{\"relatedParty\":[{\"role\":\"payer\"},{\"id\":\"p-2\"}]}",
                PaymentMethodRequest.class).firstPartyId()).isEqualTo("p-2");
        assertThat(mapper.readValue("{\"relatedParty\":[]}", PaymentMethodRequest.class)
                .firstPartyId()).isNull();
        assertThat(mapper.readValue("{\"relatedParty\":\"p-1\"}", PaymentMethodRequest.class)
                .firstPartyId()).isNull();
    }

    @Test
    void theBodyCannotSetTheStatusTheOwnerOrTheVaultToken() throws Exception {
        PaymentMethodRequest req = mapper.readValue(
                "{\"@type\":\"bnplToken\",\"details\":{\"brand\":\"klarna\",\"token\":\"k-1\"},"
                        + "\"status\":\"active\",\"ownerPartyId\":\"someone-else\","
                        + "\"pspToken\":\"tok_stolen\"}", PaymentMethodRequest.class);
        assertThat(req.type()).isEqualTo("bnplToken");
        assertThat(req.detail("token")).isEqualTo("k-1");
        assertThat(req.detail("expiry")).isNull();
        assertThat(req.firstPartyId()).isNull();
    }

    @Test
    void aNonObjectDetailsIsTheServicesOwnRefusalNotAParsers() throws Exception {
        assertThat(mapper.readValue("{\"details\":\"visa\"}", PaymentMethodRequest.class)
                .details().isObject()).isFalse();
        assertThat(mapper.readValue("{}", PaymentMethodRequest.class).details()).isNull();
    }
}
