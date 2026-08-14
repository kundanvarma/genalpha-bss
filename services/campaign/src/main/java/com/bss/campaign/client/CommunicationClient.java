package com.bss.campaign.client;

/** The delivery channel: campaign messages go out as TMF681 communications. */
public interface CommunicationClient {

    default void send(String partyId, String subject, String content) {
        send(partyId, subject, content, java.util.Map.of());
    }

    /** Send inline copy with a personalization context ({{order.id}} etc.). */
    void send(String partyId, String subject, String content, java.util.Map<String, Object> context);

    /** Send via a reusable template — communication renders the localized,
     *  tokenized copy for the given channel and context. */
    void sendTemplated(String partyId, String templateRef, String locale, String channel,
            java.util.Map<String, Object> context);
}
