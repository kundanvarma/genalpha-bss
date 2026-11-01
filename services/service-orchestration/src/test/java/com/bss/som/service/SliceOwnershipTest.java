package com.bss.som.service;

import com.bss.som.entity.ServiceInstance;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static com.bss.som.service.SliceOwnership.Outcome.CLEAR_ADDON;
import static com.bss.som.service.SliceOwnership.Outcome.END;
import static com.bss.som.service.SliceOwnership.Outcome.HAND_TO_ADDON;
import static com.bss.som.service.SliceOwnership.Outcome.NOTHING;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who pays for a line's priority (#393, review): a Priority plan, an
 * open-ended add-on, or both — and what happens to the line when one of them
 * goes away.
 */
class SliceOwnershipTest {

    private static ServiceInstance line(String owner, String addon, OffsetDateTime until) {
        ServiceInstance l = new ServiceInstance();
        l.setSliceProfile("priority");
        l.setSliceOrderId(owner);
        l.setSliceAddonOrderId(addon);
        l.setSliceUntil(until);
        return l;
    }

    @Test
    void anAddOnOnAPriorityPlan_standsBy_soReleasingItKeepsThePlansPriority() {
        ServiceInstance onPlan = line("ord-plan", "ord-addon", null);
        assertThat(SliceOwnership.planOwned(onPlan)).isTrue();
        assertThat(SliceOwnership.afterEnding(onPlan, "ord-addon")).isEqualTo(CLEAR_ADDON);
    }

    @Test
    void downgradingAPriorityPlan_handsTheSliceToTheAddOnTheHouseholdStillPaysFor() {
        assertThat(SliceOwnership.afterPlanLosesPriority(line("ord-plan", "ord-addon", null))).isEqualTo(HAND_TO_ADDON);
    }

    @Test
    void downgradingAPriorityPlan_withNoAddOn_takesThePriorityAway() {
        assertThat(SliceOwnership.afterPlanLosesPriority(line("ord-plan", null, null))).isEqualTo(END);
    }

    @Test
    void aPlanChange_neverEndsASliceTheAddOnOrAPassOwns() {
        assertThat(SliceOwnership.afterPlanLosesPriority(line("ord-addon", "ord-addon", null))).isEqualTo(NOTHING);
        assertThat(SliceOwnership.afterPlanLosesPriority(line("ord-pass", null, OffsetDateTime.now().plusHours(3))))
                .isEqualTo(NOTHING);
        ServiceInstance plain = new ServiceInstance();
        assertThat(SliceOwnership.afterPlanLosesPriority(plain)).isEqualTo(NOTHING);
    }

    @Test
    void releasingTheAddOnThatOwnsTheSlice_endsIt() {
        assertThat(SliceOwnership.afterEnding(line("ord-addon", "ord-addon", null), "ord-addon")).isEqualTo(END);
    }

    @Test
    void endingSomethingElse_changesNothing() {
        assertThat(SliceOwnership.afterEnding(line("ord-plan", "ord-addon", null), "ord-other")).isEqualTo(NOTHING);
        assertThat(SliceOwnership.afterEnding(line("ord-plan", "ord-addon", null), null)).isEqualTo(NOTHING);
    }
}
