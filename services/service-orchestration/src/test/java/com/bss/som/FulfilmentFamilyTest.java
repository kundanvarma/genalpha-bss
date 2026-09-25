package com.bss.som;

import com.bss.som.client.CatalogClient.Cfs;
import com.bss.som.service.OrchestrationService;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who decides an order item's fulfilment family: the CFS the product spec
 * names, then — only while a spec names none — the category string. This is
 * catalog-to-provisioning step 1, and the fallback is what every order did
 * before there were CFS, so an unfilled catalog changes nothing.
 */
class FulfilmentFamilyTest {

    @Test
    void theCfsDecidesEvenWhenTheCategorySaysOtherwise() {
        // a TV entitlement filed under "Mobile plans" is fulfilled as TV, not as a line
        Cfs tv = new Cfs("cfs-tv", "TV entitlement", "tv");
        assertThat(OrchestrationService.fulfilmentFamily(Optional.of(tv), "Mobile plans")).isEqualTo("tv");
        assertThat(OrchestrationService.fulfilmentFamily(Optional.of(new Cfs("p", "Partner activation", "partner")), "Devices"))
                .isEqualTo("partner");
    }

    @Test
    void aCfsThatDeclaresNoFamilyLeavesTheCategoryInCharge() {
        Cfs wholesale = new Cfs("cfs-w", "wholesale access CFS", null);
        assertThat(OrchestrationService.fulfilmentFamily(Optional.of(wholesale), "Broadband")).isEqualTo("internet");
        assertThat(OrchestrationService.fulfilmentFamily(Optional.of(wholesale), "Partner services")).isEqualTo("partner");
    }

    @Test
    void noCfsIsTheHistoricalCategoryTable() {
        assertThat(OrchestrationService.fulfilmentFamily(Optional.empty(), "Mobile plans")).isEqualTo("mobile");
        assertThat(OrchestrationService.fulfilmentFamily(Optional.empty(), "Broadband")).isEqualTo("internet");
        assertThat(OrchestrationService.fulfilmentFamily(Optional.empty(), "TV & Add-ons")).isEqualTo("tv");
        assertThat(OrchestrationService.fulfilmentFamily(Optional.empty(), "Devices")).isEqualTo("device");
        assertThat(OrchestrationService.fulfilmentFamily(Optional.empty(), "Partner services")).isEqualTo("partner");
        assertThat(OrchestrationService.fulfilmentFamily(Optional.empty(), "Security")).isEqualTo("security");
        assertThat(OrchestrationService.fulfilmentFamily(Optional.empty(), null)).isEqualTo("other");
    }
}
