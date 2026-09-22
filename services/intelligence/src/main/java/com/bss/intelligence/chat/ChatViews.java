package com.bss.intelligence.chat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** The care chat's wire shapes: what a rail opens, says, reads and escalates. */
public final class ChatViews {

    private ChatViews() {
    }

    /** A freshly opened session: the id IS the guest's capability. */
    public record ChatSessionOpened(String id) {
    }

    /** One customer turn answered: the bot's reply (null once a human has
     * taken over), the session status, and the ticket when the bot escalated. */
    @JsonPropertyOrder({"reply", "status", "ticketId"})
    public record ChatTurn(String reply, String status,
            @JsonInclude(JsonInclude.Include.NON_NULL) String ticketId) {
    }

    /** The desk replied: the session is now the human's. */
    public record AgentReply(String status) {
    }

    /** A real ticket was raised from the conversation. */
    @JsonPropertyOrder({"ticketId", "status"})
    public record EscalationReceipt(String ticketId, String status) {
    }

    /** One line of the transcript. */
    @JsonPropertyOrder({"id", "author", "body", "at"})
    public record ChatMessageView(String id, String author, String body, String at) {
    }

    /** One open conversation on the desk's list. */
    @JsonPropertyOrder({"id", "channel", "status", "partyId", "ticketId", "updatedAt", "messages", "lastMessage"})
    public record ChatSessionView(String id, String channel, String status, String partyId,
            String ticketId, String updatedAt, int messages, String lastMessage) {
    }

    /** What a customer, guest or agent typed. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatMessageRequest(String text) {
        public String textOrEmpty() {
            return text == null ? "" : text;
        }
    }

    /** How to reach the person once a human picks the chat up (optional). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatEscalateRequest(String contact) {
    }
}
