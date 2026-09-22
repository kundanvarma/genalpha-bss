package com.bss.quote.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** What the sales desk's actions hand back. */
public final class SalesReceipts {

    private SalesReceipts() {
    }

    /** The social lead-form pull: which form, how many entries the platform had, how many were new. */
    @JsonPropertyOrder({"form", "entries", "imported"})
    public record SocialImport(String form, int entries, int imported) {
    }

    /** The opportunity → quote hand-off: the deal (now linked) and the quote it produced. */
    @JsonPropertyOrder({"opportunity", "quote"})
    public record QuoteHandoff(OpportunityView opportunity, QuoteView quote) {
    }

    /** Guided selling applied to a deal: what was recommended and the deal with those lines added. */
    @JsonPropertyOrder({"applied", "recommendations", "opportunity"})
    public record GuidedApplied(int applied, List<GuidedSelling.Recommendation> recommendations,
            OpportunityView opportunity) {
    }
}
