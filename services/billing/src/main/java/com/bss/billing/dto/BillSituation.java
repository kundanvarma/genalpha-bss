package com.bss.billing.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.LocalDate;

/**
 * What is true about a bill right now, computed once from the facts and read
 * by every channel — the back office, the CSR desk, the customer's app and
 * collections. The point is that nobody derives lateness from a raw flag: an
 * approved payment arrangement means the bill is NOT overdue, everywhere, at
 * the same moment.
 *
 * <p>It carries the reason in operator language and the dates that justify
 * it, so a screen can explain itself without asking a second question.
 *
 * @param value            one of {@code issued · outstanding · partiallyPaid · paid ·
 *                         overdue · arrangement · disputed · writtenOff}
 * @param reason           why, in words a person can read aloud
 * @param originalDueDate  the day the bill first fell due (null before it is billed)
 * @param currentDueDate   the day it is due NOW — the arrangement's date when one stands
 * @param arrangementUntil the promise-to-pay date when the customer has one, else null
 * @param outstanding      what is still owed
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"value", "reason", "originalDueDate", "currentDueDate", "arrangementUntil", "outstanding",
        "@type"})
public record BillSituation(String value, String reason, LocalDate originalDueDate, LocalDate currentDueDate,
        LocalDate arrangementUntil, Money outstanding,
        @com.fasterxml.jackson.annotation.JsonProperty("@type") String type) {

    public static final String ISSUED = "issued";
    public static final String OUTSTANDING = "outstanding";
    public static final String PARTIALLY_PAID = "partiallyPaid";
    public static final String PAID = "paid";
    public static final String OVERDUE = "overdue";
    public static final String ARRANGEMENT = "arrangement";
    public static final String DISPUTED = "disputed";
    public static final String WRITTEN_OFF = "writtenOff";

    public BillSituation(String value, String reason, LocalDate originalDueDate, LocalDate currentDueDate,
            LocalDate arrangementUntil, Money outstanding) {
        this(value, reason, originalDueDate, currentDueDate, arrangementUntil, outstanding, "BillSituation");
    }
}
