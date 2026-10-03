package com.bss.catalog.service;

import com.bss.catalog.client.StockReader;
import com.bss.catalog.dto.Availability;
import com.bss.catalog.dto.Money;
import com.bss.catalog.dto.ProductOfferingDto;
import com.bss.catalog.dto.ProductOfferingPriceDto;
import com.bss.catalog.dto.ProductSpecificationDto;
import com.bss.catalog.dto.SchemaOrg;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import static com.bss.catalog.mapper.Wire.idOf;

/**
 * THE SCHEMA MAPPING: one TMF620 offering projected into schema.org types for
 * the crawler-facing page. A projection, never a second catalog — every value
 * here is already public on the catalog API, and nothing is authored.
 *
 * Three facts used to be constants and are now read from evidence:
 *
 * <ul>
 * <li><b>availability</b> — from the warehouse where the product is stock
 *     managed (a phone), and from the lifecycle and the channel list where it
 *     is not (a mobile plan is not "in stock"; it is orderable online). Never
 *     {@code InStock} by default.</li>
 * <li><b>the headline price</b> — the RECURRING charge for a subscription, with
 *     its billing period. Preferring the one-time component let an offering
 *     advertise its activation fee as its price: GenAlpha Fiber 1000 published
 *     49.00 (a fibre installation fee) where the customer pays 39.99 a month.
 *     That is a commercial misstatement, not a formatting slip.</li>
 * <li><b>the currency</b> — the price's own unit, falling back to the tenant's
 *     configured currency, never a literal.</li>
 * </ul>
 *
 * No reviews, no ratings: this page displays none, so publishing them would be
 * fabricated structured data.
 */
@Component
public class SchemaOrgProjection {

    /** schema.org ItemAvailability values, named so nothing is spelled twice. */
    private static final String IN_STOCK = "https://schema.org/InStock";
    private static final String OUT_OF_STOCK = "https://schema.org/OutOfStock";
    private static final String ONLINE_ONLY = "https://schema.org/OnlineOnly";
    private static final String IN_STORE_ONLY = "https://schema.org/InStoreOnly";
    private static final String DISCONTINUED = "https://schema.org/Discontinued";

    /** UN/CEFACT codes for the billing periods TMF620 names. */
    private static final Map<String, String> PERIOD_CODES = Map.of(
            "day", "DAY", "week", "WEE", "month", "MON", "quarter", "QAN", "year", "ANN");

    /**
     * A name written as a CODE rather than for a person: one lowercase token, or
     * camelCase run together. Such a characteristic is an internal fact
     * ({@code chargingSpecId}, {@code volte}, {@code sliceProfile}) and stays off
     * the page unless it is a choice a customer makes or the author documented it
     * — screens, and a crawler's page is a screen, speak operator language.
     */
    private static final Pattern CODE_NAME = Pattern.compile("^[a-z][A-Za-z0-9]*$");

    private final ProductOfferingPriceService prices;
    private final ProductSpecificationService specifications;
    private final StockReader stock;
    private final LifecyclePolicy lifecycle;

    public SchemaOrgProjection(ProductOfferingPriceService prices,
            ProductSpecificationService specifications, StockReader stock, LifecyclePolicy lifecycle) {
        this.prices = prices;
        this.specifications = specifications;
        this.stock = stock;
        this.lifecycle = lifecycle;
    }

    /** What the page needs: the structured document, and the two sentences a human reads. */
    public record OfferingPage(SchemaOrg.Product product, Headline headline, Headline upfront) {
    }

    /** One charge: the amount, its currency, and the period it recurs over (null = once). */
    public record Headline(BigDecimal amount, String currency, String period) {

        public String text() {
            return amount.toPlainString() + " " + currency + (period == null ? "" : "/" + period);
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
        List<ProductOfferingPriceDto> resolved = resolvedPrices(offering);
        Headline headline = headline(resolved, currencyFallback);
        Headline upfront = headline != null && headline.period() != null
                ? total(resolved, "oneTime", currencyFallback) : null;
        String canonical = absolute(baseUrl, "/shop/offering/" + offering.getId());

        SchemaOrg.Offer offer = headline == null ? null : new SchemaOrg.Offer(null, canonical,
                headline.amount().toPlainString(), headline.currency(), availability(offering),
                headline.period() == null ? null : new SchemaOrg.UnitPrice(null,
                        headline.amount().toPlainString(), headline.currency(), 1,
                        PERIOD_CODES.get(headline.period())));

        SchemaOrg.Product product = new SchemaOrg.Product(null, null, offering.getName(),
                offering.getDescription(), category(offering), offering.getId(), canonical,
                images(offering, baseUrl), SchemaOrg.Organization.named(brandName),
                characteristics(offering), offer);
        return new OfferingPage(product, headline, upfront);
    }

    /* ---------- availability: evidence, not a constant ---------- */

    /**
     * What the operator can honestly say about getting this today. Stock answers
     * where the warehouse keeps rows for the offering; where it does not — a
     * plan, a subscription, a service — the honest statement is about ordering:
     * sellable on the web is {@code OnlineOnly}, sellable only through a dealer
     * is {@code InStoreOnly}, and past its window or off the ladder is
     * {@code Discontinued}.
     *
     * The last two are a GUARD, not a path a crawler walks today: the catalog's
     * own door already answers 404 for a retired, expired or dealer-only
     * offering to an anonymous caller, so suite #246 proves the warehouse's two
     * answers and the ordering answer and says plainly that it does not reach
     * these. They are here for when SEO-1/SEO-3 make a retired offering render
     * a redirect instead of a 404 — the day this becomes reachable, it is
     * already honest rather than newly wrong.
     */
    private String availability(ProductOfferingDto offering) {
        Availability warehouse = stock.availability(offering.getId());
        if (warehouse.managed()) {
            int left = warehouse.rows().stream().mapToInt(Availability.Row::available).sum();
            return left > 0 ? IN_STOCK : OUT_OF_STOCK;
        }
        if (!lifecycle.sellableDto(offering)) {
            return DISCONTINUED;
        }
        return lifecycle.sellableDtoIn(offering, Channels.DEFAULT) ? ONLINE_ONLY : IN_STORE_ONLY;
    }

    /* ---------- the headline price ---------- */

    /**
     * The one price the Offer carries. A subscription's headline is the sum of
     * its recurring charges — the same arithmetic the shop shows a human, so the
     * two faces cannot disagree — and a bundle discount is one of those charges.
     * Only an offering with no recurring charge at all leads with its one-time
     * price.
     */
    private Headline headline(List<ProductOfferingPriceDto> resolved, String currencyFallback) {
        List<ProductOfferingPriceDto> recurring = resolved.stream()
                .filter(p -> "recurring".equals(p.getPriceType())).toList();
        if (!recurring.isEmpty()) {
            BigDecimal amount = recurring.stream().map(p -> p.getPrice().value())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            return new Headline(amount, currency(recurring, currencyFallback), period(recurring));
        }
        Headline once = total(resolved, "oneTime", currencyFallback);
        if (once != null) {
            return once;
        }
        // neither recurring nor one-time (a usage or penalty price only): say what is declared
        return resolved.isEmpty() ? null : new Headline(resolved.get(0).getPrice().value(),
                resolved.get(0).getPrice().unitOr(currencyFallback), null);
    }

    private Headline total(List<ProductOfferingPriceDto> resolved, String priceType,
            String currencyFallback) {
        List<ProductOfferingPriceDto> of = resolved.stream()
                .filter(p -> priceType.equals(p.getPriceType())).toList();
        if (of.isEmpty()) {
            return null;
        }
        BigDecimal amount = of.stream().map(p -> p.getPrice().value())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Headline(amount, currency(of, currencyFallback), null);
    }

    private static String currency(List<ProductOfferingPriceDto> of, String fallback) {
        return of.stream().map(p -> p.getPrice().unit()).filter(u -> u != null).findFirst()
                .orElse(fallback);
    }

    /** The period the recurring charge recurs over; TMF620 leaves it off for a monthly charge. */
    private static String period(List<ProductOfferingPriceDto> recurring) {
        return recurring.stream().map(ProductOfferingPriceDto::getRecurringChargePeriodType)
                .filter(p -> p != null && PERIOD_CODES.containsKey(p)).findFirst().orElse("month");
    }

    /**
     * The offering's prices, resolved against the price catalogue. A price
     * conditioned on characteristic values is left out: it depends on picks a
     * crawler has not made, exactly as a list view leaves it out for a human.
     */
    private List<ProductOfferingPriceDto> resolvedPrices(ProductOfferingDto offering) {
        List<Map<String, Object>> refs = offering.getProductOfferingPrice();
        if (refs == null) {
            return List.of();
        }
        Map<String, ProductOfferingPriceDto> index = prices.findAll(0, 500, Map.of()).items()
                .stream().collect(Collectors.toMap(ProductOfferingPriceDto::getId,
                        Function.identity(), (a, b) -> a));
        return refs.stream()
                .map(ref -> {
                    ProductOfferingPriceDto hit = index.get(idOf(ref));
                    if (hit != null) {
                        return hit;
                    }
                    // the overlay seam: a federated legacy offering embeds its price on the ref
                    if (ref.get("price") instanceof Map<?, ?> p && p.get("value") != null) {
                        ProductOfferingPriceDto dto = new ProductOfferingPriceDto();
                        dto.setId(idOf(ref));
                        dto.setPriceType(String.valueOf(ref.getOrDefault("priceType", "oneTime")));
                        dto.setPrice(Money.of(p));
                        return dto;
                    }
                    return null;
                })
                .filter(p -> p != null && p.getPrice() != null && p.getPrice().value() != null)
                .filter(p -> p.getProdSpecCharValueUse() == null || p.getProdSpecCharValueUse().isEmpty())
                .toList();
    }

    /* ---------- images ---------- */

    /** The offering's image attachments, absolute, in the order the catalog holds them. */
    private List<String> images(ProductOfferingDto offering, String baseUrl) {
        List<Map<String, Object>> attachments = offering.getAttachment();
        if (attachments == null) {
            return List.of();
        }
        List<String> urls = new ArrayList<>();
        for (Map<String, Object> attachment : attachments) {
            Object url = attachment.get("url");
            Object mime = attachment.get("mimeType");
            if (url == null || mime == null || !String.valueOf(mime).startsWith("image/")) {
                continue; // a datasheet is not a product image
            }
            String absolute = absolute(baseUrl, String.valueOf(url));
            if (!urls.contains(absolute)) {
                urls.add(absolute);
            }
        }
        return urls;
    }

    /* ---------- specification characteristics ---------- */

    /**
     * What the specification says about the product, as structured properties
     * instead of being dropped: a plan's data allowance, network and roaming, a
     * line's speeds, a device's storage and colour.
     *
     * A characteristic reaches the page when a customer can CHOOSE it (the
     * configurator already offers it to every channel), or when it is a fact the
     * author wrote for a person — a name in operator language, a description, or
     * a unit of measure. A fact named as a code and documented nowhere
     * ({@code chargingSpecId}, {@code volte}, {@code sliceProfile}) is internal
     * and stays off the page.
     */
    private List<SchemaOrg.PropertyValue> characteristics(ProductOfferingDto offering) {
        if (offering.getProductSpecification() == null || offering.getProductSpecification().id() == null) {
            return List.of();
        }
        ProductSpecificationDto spec;
        try {
            spec = specifications.findById(offering.getProductSpecification().id());
        } catch (RuntimeException e) {
            return List.of(); // a specification we cannot read is a silence, not a page failure
        }
        if (spec == null || spec.getProductSpecCharacteristic() == null) {
            return List.of();
        }
        List<SchemaOrg.PropertyValue> properties = new ArrayList<>();
        for (Map<String, Object> characteristic : spec.getProductSpecCharacteristic()) {
            SchemaOrg.PropertyValue property = property(characteristic);
            if (property != null) {
                properties.add(property);
            }
        }
        return properties;
    }

    private SchemaOrg.PropertyValue property(Map<String, Object> characteristic) {
        Object rawName = characteristic.get("name");
        if (rawName == null || String.valueOf(rawName).isBlank()) {
            return null;
        }
        String name = String.valueOf(rawName).trim();
        String description = characteristic.get("description") == null ? null
                : String.valueOf(characteristic.get("description"));
        List<?> values = characteristic.get("productSpecCharacteristicValue") instanceof List<?> list
                ? list : List.of();

        // "configurable" absent or true = a pick the channels already show; false = a fact
        boolean choice = !Boolean.FALSE.equals(characteristic.get("configurable"));
        String unit = null;
        List<String> displayed = new ArrayList<>();
        String declaredDefault = null;
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> v)) {
                continue;
            }
            if (unit == null && v.get("unitOfMeasure") != null) {
                unit = String.valueOf(v.get("unitOfMeasure"));
            }
            if (v.get("value") == null) {
                continue; // a range, not a value: the richer feed's business
            }
            String text = String.valueOf(v.get("value"));
            if (Boolean.TRUE.equals(v.get("isDefault"))) {
                declaredDefault = text;
            }
            if (!displayed.contains(text)) {
                displayed.add(text);
            }
        }
        if (!choice && CODE_NAME.matcher(name).matches() && description == null && unit == null) {
            return null; // an internal fact, named as a code
        }
        if (displayed.isEmpty()) {
            return null;
        }
        // one truth per property: the declared default, the sole value, or every
        // value a customer may pick — each of which is true of the product
        String value = declaredDefault != null ? declaredDefault : String.join(", ", displayed);
        return SchemaOrg.PropertyValue.of(name, value, unit, description);
    }

    /* ---------- small things ---------- */

    private static String category(ProductOfferingDto offering) {
        List<Map<String, Object>> categories = offering.getCategory();
        if (categories == null || categories.isEmpty() || categories.get(0).get("name") == null) {
            return null;
        }
        return String.valueOf(categories.get(0).get("name"));
    }

    /**
     * An absolute URL where the request told us the host, the path untouched
     * where it did not — a guessed host in a canonical tag is worse than a
     * relative one.
     */
    private static String absolute(String baseUrl, String path) {
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path;
        }
        return baseUrl == null || baseUrl.isBlank() ? path : baseUrl + path;
    }
}
