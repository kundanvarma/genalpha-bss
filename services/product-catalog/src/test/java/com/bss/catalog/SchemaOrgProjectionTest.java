package com.bss.catalog;

import com.bss.catalog.api.PagedResult;
import com.bss.catalog.client.StockReader;
import com.bss.catalog.dto.Availability;
import com.bss.catalog.dto.EntityRef;
import com.bss.catalog.dto.Money;
import com.bss.catalog.dto.ProductOfferingDto;
import com.bss.catalog.dto.ProductOfferingPriceDto;
import com.bss.catalog.dto.ProductSpecificationDto;
import com.bss.catalog.service.LifecyclePolicy;
import com.bss.catalog.service.ProductOfferingPriceService;
import com.bss.catalog.service.ProductSpecificationService;
import com.bss.catalog.service.SchemaOrgProjection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The three facts that used to be constants, each pinned where it is decided —
 * no fleet, no container, so the rules can be broken on purpose and watched go
 * red in seconds rather than in a rebuild.
 */
class SchemaOrgProjectionTest {

    private ProductOfferingPriceService prices;
    private ProductSpecificationService specifications;
    private StockReader stock;
    private LifecyclePolicy lifecycle;
    private SchemaOrgProjection projection;

    @BeforeEach
    void setUp() {
        prices = mock(ProductOfferingPriceService.class);
        specifications = mock(ProductSpecificationService.class);
        stock = mock(StockReader.class);
        lifecycle = mock(LifecyclePolicy.class);
        projection = new SchemaOrgProjection(prices, specifications, stock, lifecycle);
        // the default world: orderable online, nothing in a warehouse
        when(stock.availability(anyString())).thenReturn(Availability.NONE);
        when(lifecycle.sellableDto(any())).thenReturn(true);
        when(lifecycle.sellableDtoIn(any(), anyString())).thenReturn(true);
    }

    private ProductOfferingPriceDto price(String id, String name, String type, String unit,
            String amount, String period) {
        ProductOfferingPriceDto dto = new ProductOfferingPriceDto();
        dto.setId(id);
        dto.setName(name);
        dto.setPriceType(type);
        dto.setPrice(new Money(unit, new BigDecimal(amount)));
        dto.setRecurringChargePeriodType(period);
        return dto;
    }

    private ProductOfferingDto offering(ProductOfferingPriceDto... refs) {
        ProductOfferingDto dto = new ProductOfferingDto();
        dto.setId("off-1");
        dto.setName("GenAlpha Fiber 1000");
        dto.setLifecycleStatus("Active");
        dto.setProductOfferingPrice(List.of(refs).stream()
                .map(p -> Map.<String, Object>of("id", p.getId(), "@referredType", "ProductOfferingPrice"))
                .toList());
        when(prices.findAll(anyInt(), anyInt(), any()))
                .thenReturn(new PagedResult<>(List.of(refs), refs.length));
        return dto;
    }

    /**
     * THE COMMERCIAL DEFECT. A fibre line with a 39.99 monthly charge and a
     * 49.00 installation fee published 49.00 as its price, because the
     * generator preferred the one-time component. The Offer must lead with the
     * recurring charge, and say which period it recurs over.
     */
    @Test
    void aSubscriptionLeadsWithItsRecurringCharge() {
        ProductOfferingDto fibre = offering(
                price("p-install", "Fiber Installation Fee", "oneTime", "EUR", "49.00", null),
                price("p-monthly", "Fiber 1000 Monthly", "recurring", "EUR", "39.99", "month"));

        SchemaOrgProjection.OfferingPage page = projection.page(fibre, "MyGenAlpha", "EUR", "");

        assertEquals("39.99", page.product().offers().price(),
                "the Offer must carry the monthly charge, not the installation fee");
        assertEquals("MON", page.product().offers().priceSpecification().unitCode());
        assertEquals(1, page.product().offers().priceSpecification().billingDuration());
        // the one-time charge is still told to a human, beside the headline
        assertEquals("49.00", page.upfront().amount().toPlainString());
    }

    /** A bundle's headline is the sum of its recurring components, discount included. */
    @Test
    void aBundleSumsItsRecurringComponents() {
        ProductOfferingDto bundle = offering(
                price("p-mobile", "Mobile Unlimited", "recurring", "EUR", "25.00", "month"),
                price("p-fibre", "Fiber 1000", "recurring", "EUR", "39.99", "month"),
                price("p-discount", "Bundle discount", "recurring", "EUR", "-15.00", "month"),
                price("p-install", "Installation", "oneTime", "EUR", "49.00", null));

        assertEquals("49.99", projection.page(bundle, "MyGenAlpha", "EUR", "").product().offers().price());
    }

    /** With no recurring charge at all, the one-time price is the honest headline. */
    @Test
    void aOneOffPurchaseLeadsWithItsOneTimePrice() {
        ProductOfferingDto watch = offering(
                price("p-watch", "Kids Smartwatch", "oneTime", "EUR", "79.99", null));

        SchemaOrgProjection.OfferingPage page = projection.page(watch, "MyGenAlpha", "EUR", "");
        assertEquals("79.99", page.product().offers().price());
        assertNull(page.product().offers().priceSpecification(), "a one-off recurs over nothing");
        assertNull(page.upfront(), "nothing to add beside a one-time headline");
    }

    /** Availability comes from the warehouse where there are rows to read. */
    @Test
    void theWarehouseAnswersForAStockManagedProduct() {
        ProductOfferingDto box = offering(price("p-box", "Box", "oneTime", "EUR", "490.00", null));

        when(stock.availability("off-1")).thenReturn(new Availability(true,
                List.of(new Availability.Row(Map.of("boxColour", "Black"), 22),
                        new Availability.Row(Map.of("boxColour", "Icy Blue"), 0))));
        assertEquals("https://schema.org/InStock",
                projection.page(box, "MyGenAlpha", "EUR", "").product().offers().availability());

        when(stock.availability("off-1")).thenReturn(new Availability(true,
                List.of(new Availability.Row(Map.of("boxColour", "Black"), 0))));
        assertEquals("https://schema.org/OutOfStock",
                projection.page(box, "MyGenAlpha", "EUR", "").product().offers().availability());
    }

    /** A plan the warehouse knows nothing about is never "in stock". */
    @Test
    void aPlanGetsOrderingSemanticsNotStockSemantics() {
        ProductOfferingDto plan = offering(price("p", "Monthly", "recurring", "EUR", "20.00", "month"));

        assertEquals("https://schema.org/OnlineOnly",
                projection.page(plan, "MyGenAlpha", "EUR", "").product().offers().availability());

        when(lifecycle.sellableDtoIn(any(), anyString())).thenReturn(false);
        assertEquals("https://schema.org/InStoreOnly",
                projection.page(plan, "MyGenAlpha", "EUR", "").product().offers().availability());

        when(lifecycle.sellableDto(any())).thenReturn(false);
        assertEquals("https://schema.org/Discontinued",
                projection.page(plan, "MyGenAlpha", "EUR", "").product().offers().availability());
    }

    /** The price's own unit wins; the tenant's currency fills a silence. */
    @Test
    void currencyIsThePricesOwnOrTheTenantsNeverALiteral() {
        ProductOfferingDto priced = offering(price("p", "Monthly", "recurring", "NOK", "299.00", "month"));
        assertEquals("NOK", projection.page(priced, "MyNova", "NOK", "").product().offers().priceCurrency());

        ProductOfferingDto unitless = offering(price("p2", "Monthly", "recurring", null, "299.00", "month"));
        assertEquals("NOK", projection.page(unitless, "MyNova", "NOK", "").product().offers().priceCurrency(),
                "a price with no unit prices in the tenant's money");
    }

    /** Absolute URLs where the request said who it was; relative rather than guessed. */
    @Test
    void urlsAreAbsoluteOnlyWhenTheHostIsKnown() {
        ProductOfferingDto plan = offering(price("p", "Monthly", "recurring", "EUR", "20.00", "month"));
        plan.setAttachment(List.of(
                Map.of("name", "hero", "mimeType", "image/svg+xml", "url", "/doc/hero.svg"),
                Map.of("name", "sheet", "mimeType", "application/pdf", "url", "/doc/sheet.pdf")));

        assertEquals(List.of("https://shop.example/doc/hero.svg"),
                projection.page(plan, "MyGenAlpha", "EUR", "https://shop.example").product().image(),
                "a datasheet is not a product image");
        assertEquals("https://shop.example/shop/offering/off-1",
                projection.page(plan, "MyGenAlpha", "EUR", "https://shop.example").product().url());
        assertEquals("/shop/offering/off-1",
                projection.page(plan, "MyGenAlpha", "EUR", "").product().url(),
                "an unforwarded request keeps a relative URL rather than inventing a host");
    }

    /**
     * Specification characteristics reach the page as properties; an internal
     * fact named as a code and documented nowhere does not.
     */
    @Test
    void characteristicsAreProjectedAndInternalFactsAreWithheld() {
        ProductOfferingDto plan = offering(price("p", "Monthly", "recurring", "EUR", "20.00", "month"));
        plan.setProductSpecification(EntityRef.of("spec-1", "Mobile 60 GB", "ProductSpecification"));

        ProductSpecificationDto spec = new ProductSpecificationDto();
        spec.setId("spec-1");
        spec.setProductSpecCharacteristic(List.of(
                Map.of("name", "Data", "configurable", false,
                        "productSpecCharacteristicValue", List.of(Map.of("value", "60 GB", "isDefault", true))),
                Map.of("name", "EU roaming", "configurable", false,
                        "productSpecCharacteristicValue", List.of(Map.of("value", "Included", "isDefault", true))),
                Map.of("name", "downloadSpeed", "configurable", true, "description", "Download speed",
                        "productSpecCharacteristicValue", List.of(
                                Map.of("value", 100, "unitOfMeasure", "Mbit/s"),
                                Map.of("value", 1000, "unitOfMeasure", "Mbit/s", "isDefault", true))),
                Map.of("name", "chargingSpecId", "configurable", false,
                        "productSpecCharacteristicValue", List.of(Map.of("value", "RG-DATA-60"))),
                Map.of("name", "volte", "configurable", false,
                        "productSpecCharacteristicValue", List.of(Map.of("value", "true")))));
        when(specifications.findById("spec-1")).thenReturn(spec);

        List<String> published = projection.page(plan, "MyGenAlpha", "EUR", "").product()
                .additionalProperty().stream().map(p -> p.name()).toList();
        assertEquals(List.of("Data", "EU roaming", "downloadSpeed"), published);
        assertTrue(projection.page(plan, "MyGenAlpha", "EUR", "").product().additionalProperty().stream()
                .anyMatch(p -> "downloadSpeed".equals(p.name()) && "1000".equals(p.value())
                        && "Mbit/s".equals(p.unitText())),
                "the declared default and its unit of measure are the published value");
    }
}
