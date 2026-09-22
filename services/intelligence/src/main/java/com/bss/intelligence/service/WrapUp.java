package com.bss.intelligence.service;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** The after-call note drafted from what the call record shows. */
@JsonPropertyOrder({"note", "disposition", "followUp", "provider", "model"})
public record WrapUp(String note, String disposition, String followUp, String provider, String model) {
}
