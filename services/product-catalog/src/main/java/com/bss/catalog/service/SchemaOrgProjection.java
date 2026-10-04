package com.bss.catalog.service;

import com.bss.catalog.dto.ProductOfferingDto;
import com.bss.catalog.dto.SchemaOrg;
import com.bss.catalog.seo.PublicCatalog;
import com.bss.catalog.seo.PublicOffering;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * THE SCHEMA MAPPING: {@link PublicOffering} dressed in schema.org types for
 * the crawler-facing page. An <b>adapter</b>, not an assembler — every
 * commercial fact is read once by {@link PublicCatalog} and shared with the
 * sitemap, llms.txt and the agentic-commerce feed, so a price changed in the
 * catalog moves on all of them together.
 *
 * <p>What belongs here and nowhere else is schema.org's own vocabulary: that an
 * availability is a URL, that a recurring charge is a
 * {@code UnitPriceSpecification} with a UN/CEFACT period code, that a
 * characteristic is a {@code PropertyValue}. The facts themselves are decided
 * upstream.</p>
 *
 * <p>No reviews, no ratings: this page displays none, so publishing them would
 * be fabricated structured data.</p>
 */
@Component
public class SchemaOrgProjection {

    /** UN/CEFACT codes for the billing periods TMF620 names. */
    private static final Map<String, String> PERIOD_CODES = Map.of(
            "day", "DAY", "week", "WEE", "month", "MON", "quarter", "QAN", "year", "ANN");

    private final PublicCatalog catalog;

    public SchemaOrgProjection(PublicCatalog catalog) {
        this.catalog = catalog;
    }

    /** What the page needs: the structured document, and the two sentences a human reads. */
    public record OfferingPage(SchemaOrg.Product product, Headline headline, Headline upfront) {
    }

    /** One charge: the amount, its currency, and the period it recurs over (null = once). */
    public record Headline(BigDecimal amount, String currency, String period) {

        public String text() {
            return amount.toPlainString() + " " + currency + (period == null ? "" : "/" + period);
        }

        static Headline of(PublicOffering.Charge charge) {
            return charge == null ? null
                    : new Headline(charge.amount(), charge.currency(), charge.period());
        }
    }

    /**
     * @param offering the offering, as the catalog holds it
     * @param brandName the tenant's brand, in operator language
     * @param currencyFallback the tenant's configured currency, used only when a
     *        price declares no unit of its own
     * @param baseUrl scheme and host the visitor actually came in on, for
     *        absolute URLs; empty leaves URLs relative rather than guessing a host
     */
    public OfferingPage page(ProductOfferingDto offering, String brandName, String currencyFallback,
            String baseUrl) {
        return page(catalog.of(offering, currencyFallback, baseUrl), offering, brandName, baseUrl);
    }

    /** The same page from an already-projected offering — the list surfaces' path. */
    public OfferingPage page(PublicOffering view, ProductOfferingDto offering, String brandName,
            String baseUrl) {
        Headline headline = Headline.of(view.headline());
        SchemaOrg.Offer offer = headline == null ? null : new SchemaOrg.Offer(null, view.canonicalUrl(),
                headline.amount().toPlainString(), headline.currency(), view.availability().schemaOrg(),
                headline.period() == null ? null : new SchemaOrg.UnitPrice(null,
                        headline.amount().toPlainString(), headline.currency(), 1,
                        PERIOD_CODES.get(headline.period())));

        SchemaOrg.Product product = new SchemaOrg.Product(null, null, view.name(),
                view.description(), view.category(), view.id(), view.canonicalUrl(),
                PublicCatalog.images(offering, baseUrl), SchemaOrg.Organization.named(brandName),
                properties(view), offer);
        return new OfferingPage(product, headline, Headline.of(view.upfront()));
    }

    /** The specification's facts, in schema.org's shape. */
    private static List<SchemaOrg.PropertyValue> properties(PublicOffering view) {
        return view.facts().stream()
                .map(f -> SchemaOrg.PropertyValue.of(f.name(), f.value(), f.unit(), f.description()))
                .toList();
    }
}
