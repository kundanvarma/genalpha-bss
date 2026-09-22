package com.bss.intelligence.service;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** A question no article answered: the content team's to-do row. */
@JsonPropertyOrder({"id", "question", "context", "asked", "firstAsked", "lastAsked"})
public record KnowledgeGapView(String id, String question, String context, int asked,
        String firstAsked, String lastAsked) {
}
