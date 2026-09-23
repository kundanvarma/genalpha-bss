package com.bss.promotion.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** "Is this code good?" — you must already know the code. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CheckPromotionRequest(String code) {
}
