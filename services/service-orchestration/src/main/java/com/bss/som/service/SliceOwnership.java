package com.bss.som.service;

import com.bss.som.entity.ServiceInstance;

/**
 * Who pays for a line's priority (#393, review). Two things can: the line's
 * PLAN (a Priority plan names a slice profile with no end) and an open-ended
 * ADD-ON (the "Priority on my line" Pick). A line remembers the order that
 * owns its current slice ({@code sliceOrderId}) and, separately, the add-on
 * order paying for priority ({@code sliceAddonOrderId}) — which is the owner
 * when the plan gives none, and a standby when the plan does. So:
 * <ul>
 * <li>releasing the add-on never strips a plan's priority;</li>
 * <li>a plan change away from priority hands the slice to a standby add-on, or
 * ends it when nothing else pays for it.</li>
 * </ul>
 * Pure decisions on the line's fields; the orchestrator applies them.
 */
final class SliceOwnership {

    enum Outcome {
        /** The line leaves its slice. */
        END,
        /** The add-on now owns the slice; the line stays on it. */
        HAND_TO_ADDON,
        /** The add-on stops paying; the plan keeps the line on its slice. */
        CLEAR_ADDON,
        /** Nothing about this line's slice changes. */
        NOTHING
    }

    private SliceOwnership() {
    }

    /** A line on its plan's priority with no end: an open-ended slice not bought by an add-on or a pass. */
    static boolean planOwned(ServiceInstance line) {
        return line.getSliceProfile() != null && line.getSliceUntil() == null && line.getSliceOrderId() != null
                && !line.getSliceOrderId().equals(line.getSliceAddonOrderId());
    }

    /** The product bought by {@code originOrderId} was ended. */
    static Outcome afterEnding(ServiceInstance line, String originOrderId) {
        if (originOrderId == null) {
            return Outcome.NOTHING;
        }
        if (originOrderId.equals(line.getSliceOrderId())) {
            return standby(line, originOrderId) ? Outcome.HAND_TO_ADDON : Outcome.END;
        }
        return originOrderId.equals(line.getSliceAddonOrderId()) ? Outcome.CLEAR_ADDON : Outcome.NOTHING;
    }

    /** The plan changed to one that gives no priority. */
    static Outcome afterPlanLosesPriority(ServiceInstance line) {
        if (!planOwned(line)) {
            return Outcome.NOTHING;   // a pass or an add-on owns it — not the plan's to end
        }
        return standby(line, line.getSliceOrderId()) ? Outcome.HAND_TO_ADDON : Outcome.END;
    }

    private static boolean standby(ServiceInstance line, String leavingOrderId) {
        return line.getSliceAddonOrderId() != null && !line.getSliceAddonOrderId().equals(leavingOrderId);
    }
}
