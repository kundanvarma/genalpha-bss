package com.bss.som;

import com.bss.som.client.CatalogClient.SliceIntent;
import com.bss.som.seam.adapters.SliceSeam;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A boost pass is catalog data (#91): the slice intent is read off the values
 * the CFS→RFS edge consumes, never off the offering's name or a hidden spec
 * read. No profile means no intent; the numbers are parsed leniently.
 */
class SliceIntentTest {

    @Test
    void theConsumedValuesSpellTheIntent() {
        Optional<SliceIntent> intent = SliceSeam.intentOf(Map.of(
                "sliceProfile", " priority ", "boostHours", "6", "sliceChargingSpecId", "RG-DATA-60-PRIO",
                "guaranteedDlMbps", "25"));
        assertThat(intent).isPresent();
        assertThat(intent.get().profile()).isEqualTo("priority");
        assertThat(intent.get().boostHours()).isEqualTo(6);
        assertThat(intent.get().chargingSpecId()).isEqualTo("RG-DATA-60-PRIO");
        assertThat(intent.get().guaranteedDlMbps()).isEqualTo(25);
    }

    @Test
    void noProfileMeansNoIntent() {
        assertThat(SliceSeam.intentOf(Map.of("boostHours", "6"))).isEmpty();
        assertThat(SliceSeam.intentOf(Map.of("sliceProfile", "  "))).isEmpty();
        assertThat(SliceSeam.intentOf((Map<String, String>) null)).isEmpty();
    }

    @Test
    void aBadNumberIsNullNotAnError() {
        Optional<SliceIntent> intent = SliceSeam.intentOf(Map.of("sliceProfile", "priority", "boostHours", "six"));
        assertThat(intent).isPresent();
        assertThat(intent.get().boostHours()).isNull();
    }
}
