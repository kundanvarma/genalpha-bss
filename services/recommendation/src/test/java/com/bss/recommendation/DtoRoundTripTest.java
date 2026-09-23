package com.bss.recommendation;

import com.bss.recommendation.dto.AffinityRow;
import com.bss.recommendation.dto.OfferingRef;
import com.bss.recommendation.dto.PartyRef;
import com.bss.recommendation.dto.RecommendationItem;
import com.bss.recommendation.dto.RecommendationView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure Jackson: the bytes and the key order of the two rails. */
class DtoRoundTripTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private String write(Object o) throws Exception {
        return mapper.writeValueAsString(o);
    }

    @Test
    void theRailIsItemsInPriorityOrderAndNothingElse() throws Exception {
        assertThat(write(RecommendationView.of("r-1", "party-1", List.of(
                new RecommendationItem(1, OfferingRef.of("off-1", "GenAlpha One Home & Mobile")),
                new RecommendationItem(2, OfferingRef.of("off-2", "GenAlpha Family Max")))))) 
                .isEqualTo("{\"id\":\"r-1\",\"name\":\"Recommended for you\","
                        + "\"relatedParty\":[{\"id\":\"party-1\",\"role\":\"customer\"}],"
                        + "\"recommendationItem\":["
                        + "{\"priority\":1,\"offering\":{\"id\":\"off-1\","
                        + "\"name\":\"GenAlpha One Home & Mobile\","
                        + "\"@referredType\":\"ProductOffering\"}},"
                        + "{\"priority\":2,\"offering\":{\"id\":\"off-2\","
                        + "\"name\":\"GenAlpha Family Max\","
                        + "\"@referredType\":\"ProductOffering\"}}],"
                        + "\"@type\":\"Recommendation\"}");
    }

    @Test
    void aCustomerWithNothingToSuggestStillGetsAWellFormedRail() throws Exception {
        assertThat(write(RecommendationView.of("r-1", "party-1", List.of())))
                .isEqualTo("{\"id\":\"r-1\",\"name\":\"Recommended for you\","
                        + "\"relatedParty\":[{\"id\":\"party-1\",\"role\":\"customer\"}],"
                        + "\"recommendationItem\":[],\"@type\":\"Recommendation\"}");
    }

    @Test
    void anOfferingWithoutANameStillNamesItsId() throws Exception {
        assertThat(write(OfferingRef.of("off-1", null)))
                .isEqualTo("{\"id\":\"off-1\",\"name\":null,"
                        + "\"@referredType\":\"ProductOffering\"}");
        assertThat(write(PartyRef.customer("party-1")))
                .isEqualTo("{\"id\":\"party-1\",\"role\":\"customer\"}");
    }

    @Test
    void anAffinityRowShowsTheAggregateAndNothingPersonal() throws Exception {
        assertThat(write(List.of(AffinityRow.of("off-1", "Samsung Galaxy S26", 246),
                AffinityRow.of("off-2", "Apple iPhone 17", 188))))
                .isEqualTo("[{\"offering\":{\"id\":\"off-1\",\"name\":\"Samsung Galaxy S26\"},"
                        + "\"coOwners\":246},"
                        + "{\"offering\":{\"id\":\"off-2\",\"name\":\"Apple iPhone 17\"},"
                        + "\"coOwners\":188}]");
        assertThat(write(List.of())).isEqualTo("[]");
    }
}
