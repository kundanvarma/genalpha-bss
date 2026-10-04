package com.bss.catalog.seo;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * ONE PROJECTION, MANY SURFACES: the commercial facts a publicly sellable
 * offering carries, normalised once from TMF620 so the crawler page, the
 * structured data, the sitemap, llms.txt and the agentic-commerce feed cannot
 * disagree about them.
 *
 * <p>It is a <b>projection, not a database</b>. Nothing here is authored,
 * nothing is stored, and TMF620 stays the one authority — every value is read
 * from the offering, its prices and its specification at request time. The
 * reason it exists is that the same facts used to be assembled five times:
 * the agentic feed led with a fibre <i>installation fee</i> (49.00) where the
 * page led with the monthly charge (64.98), and both were published as "the
 * price" of the same bundle on the same day.</p>
 *
 * <p><b>Absence is a value.</b> A fact the specification does not carry is
 * absent here — never defaulted, never synthesised. That is the difference
 * between a factual discovery surface and a marketing feed.</p>
 *
 * @param id the offering id, as the catalog holds it
 * @param name operator language, straight from the catalog
 * @param description the offering's own description, untruncated — a surface
 *        that needs it shorter is the one that should say so
 * @param category the first category's name, or null where none is set
 * @param canonicalUrl the shop URL for this offering, absolute where the
 *        request told us the host and relative where it did not
 * @param charges every unconditioned price component, in catalog order — a
 *        LIST, because a bundle's price is not one number
 * @param headline the charge a surface leads with: the summed recurring charge
 *        where one exists, otherwise the one-time total. Null when the
 *        offering carries no resolvable price at all
 * @param upfront the one-time total that accompanies a recurring headline;
 *        null when the headline is itself one-time, or there is none
 * @param stockSource what the operator can honestly say about getting this
 *        today, from the warehouse or from the lifecycle — never a constant.
 *        Read it through {@link #availability()}: it costs a call to the
 *        warehouse, so only a surface that publishes it pays
 * @param bundle whether the catalog marks this a bundle
 * @param factsSource the specification's characteristics that are fit for a
 *        person to read; empty where the specification says nothing. Read it
 *        through {@link #facts()} — it costs a call to the specification
 * @param lastUpdate when the catalog last changed this offering
 * @param validFor the offering's own window, as the catalog holds it
 * @param provenance the catalog records every fact above was read from
 */
public record PublicOffering(
        String id,
        String name,
        String description,
        String category,
        String canonicalUrl,
        List<Charge> charges,
        Charge headline,
        Charge upfront,
        Lazy<Stock> stockSource,
        boolean bundle,
        Lazy<List<Fact>> factsSource,
        OffsetDateTime lastUpdate,
        Window validFor,
        Provenance provenance) {

    /**
     * One charge the customer actually pays: the amount, its currency, and the
     * period it recurs over. A null {@code period} means it is paid once.
     */
    public record Charge(String type, BigDecimal amount, String currency, String period, String name) {

        /** The sentence a human reads, and the string a feed prints. */
        public String text() {
            return amount.toPlainString() + " " + currency + (period == null ? "" : "/" + period);
        }

        /** The amount as the wire wants it — never a double, never localised. */
        public String plain() {
            return amount.toPlainString();
        }

        public boolean recurs() {
            return period != null;
        }
    }

    /**
     * One fact the specification declares about the product — a data allowance,
     * a speed, a storage size. {@code unit} and {@code description} are present
     * only where the author wrote them.
     */
    public record Fact(String name, String value, String unit, String description) {
    }

    /** The offering's own window, exactly as the catalog states it. */
    public record Window(String startDateTime, String endDateTime) {
    }

    /**
     * WHERE EACH FACT CAME FROM. Not optional, and not decoration: an agent or
     * a reviewer that cannot trace a published price back to the catalog row it
     * was read from is reading marketing, not a record. The ids here are the
     * ones a person can type into the catalog API.
     */
    public record Provenance(String offeringId, String specificationId, List<String> priceIds) {
    }

    /**
     * What the operator can honestly say about getting this today, in one
     * vocabulary that every surface translates from. The surfaces speak
     * different dialects — schema.org uses URLs, the agentic feed uses
     * lowercase tokens — so the translation lives on the value, once, instead
     * of being spelled out at each of the five places that needs it.
     */
    public enum Stock {

        /** The warehouse holds rows and some are free. */
        IN_STOCK("https://schema.org/InStock", "in_stock"),
        /** The warehouse holds rows and none are free. */
        OUT_OF_STOCK("https://schema.org/OutOfStock", "out_of_stock"),
        /** Not a stocked thing — a plan, a subscription — and sellable on the web. */
        ONLINE_ONLY("https://schema.org/OnlineOnly", "in_stock"),
        /** Not stocked, and sellable only through a dealer or a shop. */
        IN_STORE_ONLY("https://schema.org/InStoreOnly", "in_store_only"),
        /** Past its window, or off the ladder: nobody can buy this today. */
        DISCONTINUED("https://schema.org/Discontinued", "out_of_stock");

        private final String schemaOrg;
        private final String acp;

        Stock(String schemaOrg, String acp) {
            this.schemaOrg = schemaOrg;
            this.acp = acp;
        }

        /** The schema.org ItemAvailability URL. */
        public String schemaOrg() {
            return schemaOrg;
        }

        /**
         * The Agentic Commerce Protocol's availability token. An orderable plan
         * reads {@code in_stock} there because the feed's vocabulary has no word
         * for "not a stocked thing, and you can order it" — saying it is out of
         * stock would be false, and inventing a token would break the contract.
         */
        public String acp() {
            return acp;
        }
    }

    /**
     * What the operator can honestly say about getting this today. Computed on
     * first read, because a sitemap never asks and a shelf of forty offerings
     * would otherwise cost forty calls to the warehouse to render one.
     */
    public Stock availability() {
        return stockSource.get();
    }

    /** The specification's facts, read on demand for the same reason. */
    public List<Fact> facts() {
        return factsSource.get();
    }

    /** Whether a surface that requires a price can carry this row. */
    public boolean priced() {
        return headline != null;
    }
}
