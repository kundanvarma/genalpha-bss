package com.bss.promotion.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.util.List;

/**
 * The anonymous shop-window answer, in its two honest shapes. A bad code
 * says only that it is bad — the face never enumerates promotions, so the
 * refusal carries nothing a guesser could learn from.
 */
public sealed interface PromotionCheck {

    boolean valid();

    @JsonPropertyOrder({"valid", "name", "percentage", "durationMonths", "appliesTo"})
    record Valid(boolean valid, String name, BigDecimal percentage,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer durationMonths,
            List<String> appliesTo) implements PromotionCheck {

        public static Valid of(String name, BigDecimal percentage, Integer durationMonths,
                List<String> appliesTo) {
            return new Valid(true, name, percentage, durationMonths, appliesTo);
        }
    }

    record Invalid(boolean valid) implements PromotionCheck {

        public static final Invalid INSTANCE = new Invalid(false);
    }
}
