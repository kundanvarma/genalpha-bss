package com.bss.catalog.seo;

import com.bss.catalog.client.StockReader;
import com.bss.catalog.dto.Availability;
import com.bss.catalog.dto.Money;
import com.bss.catalog.dto.ProductOfferingDto;
import com.bss.catalog.dto.ProductOfferingPriceDto;
import com.bss.catalog.dto.ProductSpecificationDto;
import com.bss.catalog.service.LifecyclePolicy;
import com.bss.catalog.service.ProductOfferingPriceService;
import com.bss.catalog.service.ProductOfferingService;
import com.bss.catalog.service.ProductSpecificationService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.bss.catalog.mapper.Wire.idOf;

/**
 * THE ONE ASSEMBLER behind every public face of the catalog. The crawler page,
 * its structured data, {@code sitemap.xml}, {@code llms.txt} and the
 * agentic-commerce feed all read {@link PublicOffering} from here, so a price
 * changed once in the catalog moves on all of them at once.
 *
 * <p>Before this existed each surface assembled TMF620 for itself, and they
 * drifted exactly as five copies of anything drift. Measured on a running
 * fleet, one triple-play bundle was published as <b>64.98/month plus 49.00
 * once</b> on its crawlable page and as <b>49.00 one-time</b> in the feed AI
 * shopping agents ingest — the installation fee sold as the price of the
 * product. Both numbers came from the same catalog rows; only the rule for
 * choosing between them differed.</p>
 *
 * <h2>What is shared, and what is deliberately not</h2>
 *
 * Shared, because a difference here is always a defect: which offerings are
 * <b>sellable at all</b> (the lifecycle ladder and the offering's window), what
 * each one <b>costs</b>, what can honestly be said about <b>getting it</b>, and
 * which <b>specification facts</b> are fit to publish.
 *
 * <p>Not shared, because the difference is the operator's intent:</p>
 * <ul>
 * <li><b>Channel.</b> An offering may be sold on the web and withheld from AI
 *     shopping agents. Each surface passes the channel it speaks for, so that
 *     stays a decision rather than an accident.</li>
 * <li><b>A price being required.</b> The agentic feed drops an offering it
 *     cannot price — a row an agent cannot act on is noise. A sitemap entry
 *     for a page that says "talk to us" is still a legitimate page. Both ask
 *     {@link PublicOffering#priced()}; they answer it differently, on purpose.</li>
 * </ul>
 *
 * <p>Nothing here is authored or cached. TMF620 remains the single authority;
 * this is a read.</p>
 */
@Component
public class PublicCatalog {

    /** One read's worth. The shelf is paged with this, not capped by it. */
    private static final int PAGE = 200;

    /**
     * The point at which reading stops and SAYS SO.
     *
     * <p>The public surfaces used to read a flat 500 and stop, silently: a
     * tenant with 600 offerings published 500 of them and nothing anywhere
     * said which 100 were missing or that any were. Paging to exhaustion fixes
     * the common case; this ceiling exists because "to exhaustion" against a
     * catalogue of unknown size is its own hazard — one slow request can hold
     * a connection open reading a hundred thousand rows.</p>
     *
     * <p>Hitting it is not silent. {@link Shelf#truncated()} carries the fact
     * to whichever surface asked, and the discovery feed publishes it, because
     * a consumer that cannot tell a complete feed from a cut one will treat a
     * missing product as a withdrawn one.</p>
     */
    private static final int CEILING = 10_000;

    /**
     * A name written as a CODE rather than for a person: one lowercase token,
     * or camelCase run together. Such a characteristic is an internal fact
     * ({@code chargingSpecId}, {@code volte}, {@code sliceProfile}) and stays
     * off every public surface unless it is a choice a customer makes or the
     * author documented it — screens speak operator language, and a crawler's
     * page is a screen.
     */
    private static final Pattern CODE_NAME = Pattern.compile("^[a-z][A-Za-z0-9]*$");

    /** The billing periods TMF620 names, as this projection spells them. */
    static final List<String> PERIODS = List.of("day", "week", "month", "quarter", "year");

    private final ProductOfferingService offerings;
    private final ProductOfferingPriceService prices;
    private final ProductSpecificationService specifications;
    private final StockReader stock;
    private final LifecyclePolicy lifecycle;

    public PublicCatalog(ProductOfferingService offerings, ProductOfferingPriceService prices,
            ProductSpecificationService specifications, StockReader stock, LifecyclePolicy lifecycle) {
        this.offerings = offerings;
        this.prices = prices;
        this.specifications = specifications;
        this.stock = stock;
        this.lifecycle = lifecycle;
    }

    /**
     * Every offering this tenant may publicly sell through {@code channel},
     * projected. One rule decides membership — {@link LifecyclePolicy}, the
     * same one the shop and the ordering path ask — so a retired or
     * out-of-window offering disappears from all five surfaces together
     * instead of lingering in whichever one forgot to check.
     *
     * @param channel the channel the calling surface speaks for
     * @param currencyFallback the tenant's currency, used only where a price
     *        declares no unit of its own
     * @param baseUrl scheme and host the visitor came in on, for absolute URLs
     */
    public List<PublicOffering> sellable(String channel, String currencyFallback, String baseUrl) {
        return shelf(channel, currencyFallback, baseUrl).rows();
    }

    /**
     * The whole sellable shelf, read to exhaustion, and whether it was cut
     * short. Surfaces that can say so should say so.
     */
    public Shelf shelf(String channel, String currencyFallback, String baseUrl) {
        Map<String, ProductOfferingPriceDto> index = priceIndex();
        List<PublicOffering> rows = new ArrayList<>();
        boolean truncated = false;
        for (int offset = 0; offset < CEILING; offset += PAGE) {
            List<ProductOfferingDto> page = offerings
                    .findAll(offset, PAGE, Map.of("lifecycleStatus", "Active")).items();
            for (ProductOfferingDto offering : page) {
                if (lifecycle.sellableDtoIn(offering, channel)) {
                    rows.add(project(offering, index, currencyFallback, baseUrl));
                }
            }
            if (page.size() < PAGE) {
                return new Shelf(List.copyOf(rows), false);
            }
            truncated = offset + PAGE >= CEILING;
        }
        return new Shelf(List.copyOf(rows), truncated);
    }

    /** What was published, and whether that is all of it. */
    public record Shelf(List<PublicOffering> rows, boolean truncated) {
    }

    /** One offering, projected — for the page that was asked for by id. */
    public PublicOffering of(ProductOfferingDto offering, String currencyFallback, String baseUrl) {
        return project(offering, priceIndex(), currencyFallback, baseUrl);
    }

    /* ---------- the projection itself ---------- */

    private PublicOffering project(ProductOfferingDto offering,
            Map<String, ProductOfferingPriceDto> index, String currencyFallback, String baseUrl) {
        List<ProductOfferingPriceDto> resolved = resolvedPrices(offering, index);
        List<PublicOffering.Charge> charges = resolved.stream()
                .map(p -> charge(p, currencyFallback)).toList();
        PublicOffering.Charge headline = headline(charges);
        PublicOffering.Charge upfront = headline != null && headline.recurs()
                ? sum(charges, "oneTime", null) : null;

        return new PublicOffering(
                offering.getId(),
                offering.getName(),
                offering.getDescription(),
                category(offering),
                absolute(baseUrl, "/shop/offering/" + offering.getId()),
                charges,
                headline,
                upfront,
                Lazy.of(() -> availability(offering)),
                Boolean.TRUE.equals(offering.getIsBundle()),
                Lazy.of(() -> facts(offering)),
                offering.getLastUpdate(),
                window(offering),
                new PublicOffering.Provenance(offering.getId(), specificationId(offering),
                        resolved.stream().map(ProductOfferingPriceDto::getId).filter(i -> i != null).toList()));
    }

    /* ---------- money ---------- */

    private static PublicOffering.Charge charge(ProductOfferingPriceDto p, String currencyFallback) {
        String period = "recurring".equals(p.getPriceType()) ? period(p) : null;
        return new PublicOffering.Charge(p.getPriceType(), p.getPrice().value(),
                p.getPrice().unitOr(currencyFallback), period, p.getName());
    }

    /**
     * The one charge a surface leads with. A subscription's headline is the sum
     * of its recurring charges — the arithmetic the shop shows a human, so the
     * faces cannot disagree — and a bundle discount is one of those charges.
     * Only an offering with no recurring charge at all leads with its one-time
     * price.
     *
     * <p>Leading with the one-time component is the mistake this guards: it let
     * a fibre bundle advertise its 49.00 installation fee where the customer
     * pays 64.98 a month. That is a commercial misstatement, not a formatting
     * slip, and it was live in the agentic feed while the page had it right.</p>
     */
    private static PublicOffering.Charge headline(List<PublicOffering.Charge> charges) {
        PublicOffering.Charge recurring = sum(charges, "recurring", periodOf(charges));
        if (recurring != null) {
            return recurring;
        }
        PublicOffering.Charge once = sum(charges, "oneTime", null);
        if (once != null) {
            return once;
        }
        // neither recurring nor one-time (a usage or penalty price only): say what is declared
        return charges.isEmpty() ? null : charges.get(0);
    }

    private static PublicOffering.Charge sum(List<PublicOffering.Charge> charges, String type, String period) {
        List<PublicOffering.Charge> of = charges.stream()
                .filter(c -> type.equals(c.type())).toList();
        if (of.isEmpty()) {
            return null;
        }
        BigDecimal amount = of.stream().map(PublicOffering.Charge::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new PublicOffering.Charge(type, amount, of.get(0).currency(), period, null);
    }

    /** The period the recurring charges recur over; TMF620 leaves it off for a monthly charge. */
    private static String periodOf(List<PublicOffering.Charge> charges) {
        return charges.stream().filter(c -> "recurring".equals(c.type()))
                .map(PublicOffering.Charge::period).filter(p -> p != null && PERIODS.contains(p))
                .findFirst().orElse("month");
    }

    private static String period(ProductOfferingPriceDto p) {
        String declared = p.getRecurringChargePeriodType();
        return declared != null && PERIODS.contains(declared) ? declared : "month";
    }

    /**
     * The offering's prices, resolved against the price catalogue. A price
     * conditioned on characteristic values is left out: it depends on picks no
     * crawler and no agent has made, exactly as a list view leaves it out for a
     * human.
     */
    private List<ProductOfferingPriceDto> resolvedPrices(ProductOfferingDto offering,
            Map<String, ProductOfferingPriceDto> index) {
        List<Map<String, Object>> refs = offering.getProductOfferingPrice();
        if (refs == null) {
            return List.of();
        }
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

    /**
     * Every price the tenant holds, read to exhaustion. A price the index
     * misses makes its offering look unpriced, which removes it from the
     * agentic feed entirely — so a cut here is not a cosmetic loss.
     */
    private Map<String, ProductOfferingPriceDto> priceIndex() {
        Map<String, ProductOfferingPriceDto> index = new LinkedHashMap<>();
        for (int offset = 0; offset < CEILING; offset += PAGE) {
            List<ProductOfferingPriceDto> page = prices.findAll(offset, PAGE, Map.of()).items();
            for (ProductOfferingPriceDto price : page) {
                index.putIfAbsent(price.getId(), price);
            }
            if (page.size() < PAGE) {
                break;
            }
        }
        return index;
    }

    /* ---------- availability: evidence, not a constant ---------- */

    /**
     * What the operator can honestly say about getting this today. Stock answers
     * where the warehouse keeps rows for the offering; where it does not — a
     * plan, a subscription, a service — the honest statement is about ordering.
     *
     * <p><b>Cost, stated rather than hidden:</b> this asks the warehouse about
     * one offering, and the warehouse has no bulk read. A surface that
     * publishes availability for a whole shelf therefore makes one call per
     * row — today only the agentic feed does. {@link Lazy} keeps every other
     * surface from paying for it, and a bulk availability read is the next
     * thing worth building. The alternative, which this replaced, was to
     * publish the constant {@code in_stock} for everything: fast, and false.</p>
     */
    private PublicOffering.Stock availability(ProductOfferingDto offering) {
        Availability warehouse = stock.availability(offering.getId());
        if (warehouse.managed()) {
            int left = warehouse.rows().stream().mapToInt(Availability.Row::available).sum();
            return left > 0 ? PublicOffering.Stock.IN_STOCK : PublicOffering.Stock.OUT_OF_STOCK;
        }
        if (!lifecycle.sellableDto(offering)) {
            return PublicOffering.Stock.DISCONTINUED;
        }
        return lifecycle.sellableDtoIn(offering, com.bss.catalog.service.Channels.DEFAULT)
                ? PublicOffering.Stock.ONLINE_ONLY : PublicOffering.Stock.IN_STORE_ONLY;
    }

    /* ---------- specification facts ---------- */

    /**
     * What the specification says about the product: a plan's data allowance,
     * network and roaming, a line's speeds, a device's storage and colour.
     *
     * <p>A characteristic reaches a public surface when a customer can CHOOSE
     * it, or when it is a fact the author wrote for a person — a name in
     * operator language, a description, or a unit of measure. A fact named as a
     * code and documented nowhere is internal and stays off.</p>
     */
    private List<PublicOffering.Fact> facts(ProductOfferingDto offering) {
        String specId = specificationId(offering);
        if (specId == null) {
            return List.of();
        }
        ProductSpecificationDto spec;
        try {
            spec = specifications.findById(specId);
        } catch (RuntimeException e) {
            return List.of(); // a specification we cannot read is a silence, not a page failure
        }
        if (spec == null || spec.getProductSpecCharacteristic() == null) {
            return List.of();
        }
        List<PublicOffering.Fact> facts = new ArrayList<>();
        for (Map<String, Object> characteristic : spec.getProductSpecCharacteristic()) {
            PublicOffering.Fact fact = fact(characteristic);
            if (fact != null) {
                facts.add(fact);
            }
        }
        return List.copyOf(facts);
    }

    private PublicOffering.Fact fact(Map<String, Object> characteristic) {
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
                continue; // a range, not a value
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
        // one truth per fact: the declared default, the sole value, or every
        // value a customer may pick — each of which is true of the product
        String value = declaredDefault != null ? declaredDefault : String.join(", ", displayed);
        return new PublicOffering.Fact(name, value, unit, description);
    }

    /* ---------- small things ---------- */

    private static String specificationId(ProductOfferingDto offering) {
        return offering.getProductSpecification() == null ? null
                : offering.getProductSpecification().id();
    }

    private static String category(ProductOfferingDto offering) {
        List<Map<String, Object>> categories = offering.getCategory();
        if (categories == null || categories.isEmpty() || categories.get(0).get("name") == null) {
            return null;
        }
        return String.valueOf(categories.get(0).get("name"));
    }

    private static PublicOffering.Window window(ProductOfferingDto offering) {
        Map<String, String> validFor = offering.getValidFor();
        if (validFor == null || validFor.isEmpty()) {
            return null;
        }
        return new PublicOffering.Window(validFor.get("startDateTime"), validFor.get("endDateTime"));
    }

    /**
     * An absolute URL where the request told us the host, the path untouched
     * where it did not — a guessed host in a canonical tag is worse than a
     * relative one.
     */
    static String absolute(String baseUrl, String path) {
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path;
        }
        return baseUrl == null || baseUrl.isBlank() ? path : baseUrl + path;
    }

    /** The offering's image attachments, absolute, in the order the catalog holds them. */
    public static List<String> images(ProductOfferingDto offering, String baseUrl) {
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
        return List.copyOf(urls);
    }
}
