package com.bss.communication.dto;

/** What a template (or an inline body with tokens) actually renders to. */
public record RenderedMessage(String subject, String content, String messageType) {
}
