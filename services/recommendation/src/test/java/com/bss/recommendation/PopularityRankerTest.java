package com.bss.recommendation;

import com.bss.recommendation.client.CommerceClients;
import com.bss.recommendation.rank.PopularityRanker;
import com.bss.recommendation.security.TenantScope;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** The crowd decides within groups; bundles keep their head start. */
class PopularityRankerTest {

    private final CommerceClients.InventoryClient inventory =
            Mockito.mock(CommerceClients.InventoryClient.class);
    private final TenantScope tenantScope = Mockito.mock(TenantScope.class);
    private final PopularityRanker ranker = new PopularityRanker(inventory, tenantScope, 60);

    private static Map<String, Object> owned(String offeringId) {
        return Map.of("productOffering", Map.of("id", offeringId));
    }

    private static Map<String, Object> offering(String id, boolean bundle) {
        return Map.of("id", id, "name", id, "isBundle", bundle);
    }

    @Test
    void popularPlansOutrankObscureOnesButNeverBundles() {
        when(tenantScope.currentTenantId()).thenReturn("genalpha");
        when(inventory.allProducts()).thenReturn(List.of(
                owned("plan-popular"), owned("plan-popular"), owned("plan-popular"),
                owned("plan-niche")));

        List<Map<String, Object>> ranked = ranker.rank(List.of(
                offering("plan-niche", false),
                offering("plan-popular", false),
                offering("bundle-fresh", true)));

        assertThat(ranked).extracting(o -> o.get("id"))
                .containsExactly("bundle-fresh", "plan-popular", "plan-niche");
    }

    @Test
    void inventoryOutageMeansUnrankedNotBroken() {
        when(tenantScope.currentTenantId()).thenReturn("genalpha");
        when(inventory.allProducts()).thenThrow(new IllegalStateException("down"));

        List<Map<String, Object>> ranked = ranker.rank(List.of(
                offering("a", false), offering("b", false)));
        assertThat(ranked).hasSize(2);
    }

    @Test
    void adoptionSnapshotsAreTenantLocal() {
        when(tenantScope.currentTenantId()).thenReturn("genalpha");
        when(inventory.allProducts()).thenReturn(List.of(owned("plan-x")));
        ranker.rank(List.of(offering("plan-x", false)));

        // Nova's snapshot is computed from nova's inventory, not genalpha's cache.
        when(tenantScope.currentTenantId()).thenReturn("nova");
        when(inventory.allProducts()).thenReturn(List.of(owned("plan-y")));
        List<Map<String, Object>> ranked = ranker.rank(List.of(
                offering("plan-x", false), offering("plan-y", false)));
        assertThat(ranked.get(0).get("id")).isEqualTo("plan-y");
    }
}
