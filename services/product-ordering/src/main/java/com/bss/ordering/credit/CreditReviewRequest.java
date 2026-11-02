package com.bss.ordering.credit;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * "Ask a person to review this" (#422). The body is a record so it cannot
 * name the order, the owner or the tenant: those come from the path, the
 * token and the store. The note is optional; a missing body is {@link #EMPTY}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreditReviewRequest(String note) {

    public static final CreditReviewRequest EMPTY = new CreditReviewRequest(null);

    /** Longest note a customer may add — a paragraph, not a document. */
    public static final int MAX_NOTE = 1000;
}
