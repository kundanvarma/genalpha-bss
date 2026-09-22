package com.bss.intelligence.client;

/** An article the ontology wrote for a screen, in the knowledge base's own shape. */
public record KnowledgeArticle(String id, String title, String body, String tags, String lastUpdate) {
}
