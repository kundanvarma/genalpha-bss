package com.bss.catalog;

import com.bss.catalog.dto.SchemaOrg;
import com.bss.catalog.mapper.JsonLd;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The structured-data document is written by a serialiser, so the characters
 * that used to break it are values now. Pure Jackson — no context, no fleet,
 * seconds to run, and it fails before an image is ever built.
 */
class SchemaOrgSerialisationTest {

    /** The name from the ticket: a real quote pair and a real backslash. */
    private static final String NAME = "Fiber 500 \"Pro\" \\ Home";
    private static final String DESCRIPTION =
            "Line one, with a quote (\") and a backslash (\\).\n"
                    + "Line two, with an ampersand (&) and a </script> tag.";

    private final JsonLd jsonLd = new JsonLd();
    private final ObjectMapper json = new ObjectMapper();

    private SchemaOrg.Product fixture() {
        return new SchemaOrg.Product(null, null, NAME, DESCRIPTION, "Broadband", "off-1",
                "http://shop.example/shop/offering/off-1",
                List.of("http://shop.example/img/hero.svg"),
                SchemaOrg.Organization.named("MyGenAlpha"),
                List.of(SchemaOrg.PropertyValue.of("Data", "Unlimited", null, null)),
                new SchemaOrg.Offer(null, "http://shop.example/shop/offering/off-1", "299.00",
                        "EUR", "https://schema.org/OnlineOnly",
                        new SchemaOrg.UnitPrice(null, "299.00", "EUR", 1, "MON")));
    }

    @Test
    void aQuotedNameRoundTripsThroughAParsableDocument() throws Exception {
        String document = jsonLd.write(fixture());
        JsonNode read = json.readTree(document);          // well formed, or this throws
        assertEquals(NAME, read.get("name").asText());
        assertEquals(DESCRIPTION, read.get("description").asText());
        assertTrue(read.get("description").asText().contains("\n"), "the newline survived");
        assertTrue(read.get("description").asText().contains("\\"), "the backslash survived");
    }

    @Test
    void nothingCanCloseTheScriptElementItRidesIn() {
        String document = jsonLd.write(fixture());
        assertFalse(document.contains("</script"), "a value closed the script element");
        assertFalse(document.contains("<"), "an unescaped < reached the document");
        assertFalse(document.contains(">"), "an unescaped > reached the document");
        assertFalse(document.contains("&"), "an unescaped & reached the document");
        assertTrue(document.contains("\\u003C/script\\u003E"), "the tag is there, as text");
    }

    @Test
    void contextLeadsAndTheOfferDeclaresItsPeriod() throws Exception {
        String document = jsonLd.write(fixture());
        assertTrue(document.startsWith("{\"@context\":\"https://schema.org\",\"@type\":\"Product\""),
                "@context and @type lead the document: " + document.substring(0, 60));
        JsonNode offer = json.readTree(document).get("offers");
        assertEquals("Offer", offer.get("@type").asText());
        assertEquals("https://schema.org/OnlineOnly", offer.get("availability").asText());
        assertEquals("UnitPriceSpecification", offer.get("priceSpecification").get("@type").asText());
        assertEquals("MON", offer.get("priceSpecification").get("unitCode").asText());
    }

    @Test
    void whatThereIsNoneOfIsLeftOff() throws Exception {
        SchemaOrg.Product bare = new SchemaOrg.Product(null, null, "Plain", null, null, "off-2",
                "/shop/offering/off-2", List.of(), SchemaOrg.Organization.named("MyGenAlpha"),
                List.of(), null);
        JsonNode read = json.readTree(jsonLd.write(bare));
        assertFalse(read.has("description"), "an absent description must not print as empty");
        assertFalse(read.has("image"), "an empty image list must not print");
        assertFalse(read.has("additionalProperty"), "an empty property list must not print");
        assertFalse(read.has("offers"), "an unpriced offering publishes no Offer");
        assertFalse(read.has("aggregateRating"), "no ratings are ever published");
        assertFalse(read.has("review"), "no reviews are ever published");
    }

    /**
     * The pre-change generator, kept as the thing that must be seen to fail:
     * the same concatenation and the same four-replacement escaping that stood
     * in GeoController. Given the fixture's name it emits a document no JSON
     * parser accepts — which is the whole defect.
     */
    @Test
    void thePreChangeGeneratorEmittedUnparsableJson() {
        String escaped = DESCRIPTION.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
        String old = "{\"@context\":\"https://schema.org\",\"@type\":\"Product\","
                + "\"name\":\"" + NAME + "\",\"description\":\"" + escaped + "\"}";
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(
                        tools.jackson.core.JacksonException.class,
                        () -> json.readTree(old))
                .getMessage().length() > 0,
                "the old generator must be unparsable, or this test proves nothing");
    }
}
