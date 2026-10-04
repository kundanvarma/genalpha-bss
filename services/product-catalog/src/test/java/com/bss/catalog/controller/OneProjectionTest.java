package com.bss.catalog.controller;

import com.bss.catalog.client.StockReader;
import com.bss.catalog.dto.AcpProductFeed;
import com.bss.catalog.dto.Availability;
import com.bss.catalog.dto.Money;
import com.bss.catalog.dto.ProductOfferingDto;
import com.bss.catalog.dto.ProductOfferingPriceDto;
import com.bss.catalog.api.PagedResult;
import com.bss.catalog.seo.PublicCatalog;
import com.bss.catalog.seo.PublicOffering;
import com.bss.catalog.service.LifecyclePolicy;
import com.bss.catalog.service.ProductOfferingPriceService;
import com.bss.catalog.service.ProductOfferingService;
import com.bss.catalog.service.ProductSpecificationService;
import com.bss.catalog.service.SchemaOrgProjection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ONE PROJECTION, MANY SURFACES — the claim that rots silently, pinned.
 *
 * <p>These are the facts that were measured disagreeing on a running fleet
 * before {@link PublicCatalog} existed: a triple-play bundle whose crawlable
 * page said <b>64.98 a month plus 49.00 once</b> while the feed AI shopping
 * agents read said <b>49.00 one-time</b> — the installation fee published as
 * the price of the product.</p>
 *
 * <p>Without a test that holds the faces beside each other, "one projection"
 * degrades into a fourth assembler within a release or two, and nothing goes
 * red when it does.</p>
 */
class OneProjectionTest {

    private static final String BUNDLE = "bundle-1";

    private ProductOfferingService offerings;
    private ProductOfferingPriceService prices;
    private ProductSpecificationService specifications;
    private StockReader stock;
    private LifecyclePolicy lifecycle;
    private PublicCatalog catalog;
    private SchemaOrgProjection schemaOrg;

    private final List<ProductOfferingPriceDto> shelf = new ArrayList<>();

    @BeforeEach
    void setUp() {
        offerings = mock(ProductOfferingService.class);
        prices = mock(ProductOfferingPriceService.class);
        specifications = mock(ProductSpecificationService.class);
        stock = mock(StockReader.class);
        lifecycle = mock(LifecyclePolicy.class);
        catalog = new PublicCatalog(offerings, prices, specifications, stock, lifecycle);
        schemaOrg = new SchemaOrgProjection(catalog);

        when(stock.availability(anyString())).thenReturn(Availability.NONE);
        when(lifecycle.sellableDto(any())).thenReturn(true);
        when(lifecycle.sellableDtoIn(any(), anyString())).thenReturn(true);
        when(prices.findAll(anyInt(), anyInt(), any()))
                .thenAnswer(i -> new PagedResult<>(List.copyOf(shelf), shelf.size()));
    }

    /* ---------- the fixture: the bundle that was published two ways ---------- */

    private ProductOfferingDto triplePlay() {
        shelf.clear();
        shelf.add(price("p-mobile", "Mobile Unlimited 5G Monthly", "recurring", "25.00", "month"));
        shelf.add(price("p-fiber", "Fiber 1000 Monthly", "recurring", "39.99", "month"));
        shelf.add(price("p-discount", "Bundle Discount", "recurring", "-15.00", "month"));
        shelf.add(price("p-tv", "TV Max Monthly", "recurring", "14.99", "month"));
        shelf.add(price("p-install", "Fiber Installation Fee", "oneTime", "49.00", null));

        ProductOfferingDto offering = new ProductOfferingDto();
        offering.setId(BUNDLE);
        offering.setName("GenAlpha One Home & Mobile");
        offering.setDescription("Triple-play bundle");
        offering.setIsBundle(Boolean.TRUE);
        offering.setProductOfferingPrice(shelf.stream()
                .map(p -> Map.<String, Object>of("id", p.getId())).toList());
        return offering;
    }

    private static ProductOfferingPriceDto price(String id, String name, String type, String amount,
            String period) {
        ProductOfferingPriceDto dto = new ProductOfferingPriceDto();
        dto.setId(id);
        dto.setName(name);
        dto.setPriceType(type);
        dto.setPrice(new Money("EUR", new BigDecimal(amount)));
        dto.setRecurringChargePeriodType(period);
        return dto;
    }

    /* ---------- the proofs ---------- */

    @Test
    void everySurfaceLeadsWithTheSameCharge() {
        ProductOfferingDto offering = triplePlay();
        PublicOffering view = catalog.of(offering, "EUR", "");

        AcpProductFeed.Item feedRow = AcpFeedController.item(view);
        SchemaOrgProjection.OfferingPage page = schemaOrg.page(view, offering, "GenAlpha", "");

        assertEquals("64.98", view.headline().plain(), "25.00 + 39.99 - 15.00 + 14.99");
        assertEquals("64.98", page.product().offers().price(),
                "the crawler page leads with the monthly charge");
        assertEquals("64.98", feedRow.price().amount(),
                "and so does the feed shopping agents read — this is the bug that was live");
        assertEquals("recurring", feedRow.priceType());
        assertEquals("month", feedRow.recurringPeriod());
        assertEquals("49.00", view.upfront().plain(), "the installation fee is beside it, not instead of it");
    }

    @Test
    void theInstallationFeeIsNeverPublishedAsThePrice() {
        PublicOffering view = catalog.of(triplePlay(), "EUR", "");
        assertEquals("oneTime", view.upfront().type());
        assertTrue(view.headline().recurs(),
                "a bundle with a monthly charge must never lead with a one-off fee");
    }

    @Test
    void aPriceChangedOnceMovesOnEverySurface() {
        ProductOfferingDto offering = triplePlay();
        assertEquals("64.98", catalog.of(offering, "EUR", "").headline().plain());

        // the operator raises the fibre line by ten — one row, in the catalog
        shelf.removeIf(p -> "p-fiber".equals(p.getId()));
        shelf.add(price("p-fiber", "Fiber 1000 Monthly", "recurring", "49.99", "month"));

        PublicOffering after = catalog.of(offering, "EUR", "");
        AcpProductFeed.Item feedRow = AcpFeedController.item(after);
        SchemaOrgProjection.OfferingPage page = schemaOrg.page(after, offering, "GenAlpha", "");

        assertEquals("74.98", after.headline().plain());
        assertEquals("74.98", page.product().offers().price(), "the page moved");
        assertEquals("74.98", feedRow.price().amount(), "and the agent feed moved with it");
    }

    @Test
    void availabilityIsOneDecisionWornTwoWays() {
        PublicOffering view = catalog.of(triplePlay(), "EUR", "");
        assertEquals(PublicOffering.Stock.ONLINE_ONLY, view.availability());
        assertEquals("https://schema.org/OnlineOnly",
                schemaOrg.page(view, triplePlay(), "GenAlpha", "").product().offers().availability());
        assertEquals("in_stock", AcpFeedController.item(view).availability(),
                "the feed's vocabulary has no word for 'orderable but not stocked'");
    }

    @Test
    void everyPublishedFactCanBeTracedBackToItsCatalogRow() {
        PublicOffering view = catalog.of(triplePlay(), "EUR", "");
        assertNotNull(view.provenance());
        assertEquals(BUNDLE, view.provenance().offeringId());
        assertEquals(List.of("p-mobile", "p-fiber", "p-discount", "p-tv", "p-install"),
                view.provenance().priceIds(),
                "an agent that cannot trace a price to its row is reading marketing");
    }

    /**
     * One projection must not mean one expensive projection. The sitemap reads
     * a URL and a date and nothing else; if building the shelf asked the
     * warehouse and the specification about every offering anyway, rendering it
     * would cost eighty downstream calls. It did, briefly: 115 seconds and a
     * 500 on a forty-offering shelf. The cost is pinned here rather than
     * rediscovered in production.
     */
    @Test
    void aSurfaceOnlyPaysForTheFactsItReads() {
        ProductOfferingDto offering = triplePlay();
        when(offerings.findAll(anyInt(), anyInt(), any()))
                .thenReturn(new PagedResult<>(List.of(offering), 1));

        List<PublicOffering> shelf = catalog.sellable("web", "EUR", "");
        assertEquals(1, shelf.size());
        assertEquals("64.98", shelf.get(0).headline().plain(), "the price is still there");

        org.mockito.Mockito.verify(stock, org.mockito.Mockito.never()).availability(anyString());
        org.mockito.Mockito.verify(specifications, org.mockito.Mockito.never()).findById(anyString());

        // ...and the surfaces that DO publish them still get them
        assertEquals(PublicOffering.Stock.ONLINE_ONLY, shelf.get(0).availability());
        org.mockito.Mockito.verify(stock, org.mockito.Mockito.times(1)).availability(anyString());
        shelf.get(0).availability();
        org.mockito.Mockito.verify(stock, org.mockito.Mockito.times(1)).availability(anyString());
    }

    @Test
    void anOfferingWithNoPriceIsCarriedByTheSitemapAndDroppedByTheFeed() {
        shelf.clear();
        ProductOfferingDto unpriced = new ProductOfferingDto();
        unpriced.setId("talk-to-us");
        unpriced.setName("Enterprise fibre");
        PublicOffering view = catalog.of(unpriced, "EUR", "");

        assertTrue(view.charges().isEmpty());
        assertEquals(false, view.priced(),
                "the feed drops it — a row an agent cannot act on is noise");
        assertTrue(view.facts().isEmpty(), "a specification that says nothing adds nothing");
    }
}
