package com.bss.knowledge;

import com.bss.knowledge.api.Json;
import com.bss.knowledge.dto.ArticleRequest;
import com.bss.knowledge.dto.ArticleView;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The article record writes the bytes the map used to write — every key, in
 * order, nulls included — and the door keeps the map's leniency. Pure Jackson,
 * configured as Spring Boot configures it — no context, no database.
 */
class DtoRoundTripTest {

    private final ObjectMapper json = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static final OffsetDateTime T = OffsetDateTime.parse("2026-09-23T10:00:00Z");

    @Test
    void anArticleWritesEveryKeyInOrderIncludingTheEmptyOnes() throws Exception {
        assertEquals("{\"id\":\"a-1\",\"href\":\"/tmf-api/knowledgeManagement/v4/article/a-1\","
                + "\"title\":\"Tickets: raise, escalate, close\",\"body\":\"How the desk works.\","
                + "\"tags\":\"csr:tickets, desk\",\"category\":\"Agent desk\",\"audience\":\"csr\","
                + "\"status\":\"published\",\"lastUpdate\":\"2026-09-23T10:00:00Z\",\"@type\":\"Article\"}",
                json.writeValueAsString(new ArticleView("a-1",
                        "/tmf-api/knowledgeManagement/v4/article/a-1",
                        "Tickets: raise, escalate, close", "How the desk works.",
                        "csr:tickets, desk", "Agent desk", "csr", "published", T)));

        String bare = json.writeValueAsString(
                new ArticleView("a-2", "/h", "t", "b", null, null, "customer", "published", null));
        assertTrue(bare.contains("\"tags\":null"));
        assertTrue(bare.contains("\"category\":null"));
        assertTrue(bare.contains("\"lastUpdate\":null"));
    }

    @Test
    void theDoorKeepsTheMapsLeniency() throws Exception {
        ArticleRequest r = json.readValue(
                "{\"title\":7,\"body\":9,\"status\":null,\"somethingElse\":true}", ArticleRequest.class);
        assertEquals("7", Json.valueOfLike(r.title()));
        assertEquals("9", Json.valueOfLike(r.body()));
        assertNull(r.id());
        // absent and explicit-null both mean "leave this alone"
        assertFalse(Json.present(r.status()));
        assertFalse(Json.present(r.audience()));
    }

    @Test
    void theAudienceVocabularyPrintsInTheOrderTheWireAlreadyHas() {
        Set<String> audiences = new LinkedHashSet<>(
                List.of("customer", "csr", "productOwner", "all", "sales"));
        assertEquals("audience must be one of [customer, csr, productOwner, all, sales]",
                "audience must be one of " + audiences);
    }
}
