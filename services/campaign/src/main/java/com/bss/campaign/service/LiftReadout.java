package com.bss.campaign.service;

import com.bss.campaign.decision.policy.AlwaysValid;
import com.bss.campaign.dto.LiftInterval;

/** Treated against holdout, said honestly: the range, and a sentence when the range is not yet a measurement. */
final class LiftReadout {

    private LiftReadout() {
    }

    static LiftInterval interval(long treated, long treatedConv, long heldOut, long holdoutConv) {
        if (treated == 0 || heldOut == 0) {
            return null;
        }
        AlwaysValid.Read read = AlwaysValid.compare(treated, treatedConv, heldOut, holdoutConv,
                AlwaysValid.DEFAULT_EFFECT_SD, AlwaysValid.DEFAULT_ALPHA);
        return new LiftInterval(AlwaysValid.points(read.low()), AlwaysValid.points(read.high()));
    }

    static String note(long heldOut, LiftInterval interval) {
        if (heldOut > 0 && heldOut < 5) {
            return "holdout under 5 people — the lift is an anecdote, not a measurement";
        }
        if (interval != null && interval.spansZero()) {
            return "the lift could still be nothing — it lies between " + interval.low() + " and " + interval.high()
                    + " points — keep it running";
        }
        return null;
    }
}
