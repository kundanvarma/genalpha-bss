package com.bss.campaign.client;

/** The delivery channel: campaign messages go out as TMF681 communications. */
public interface CommunicationClient {

    /** What actually happened to a send. Communication answers 200 even when
     * it declines to deliver (its own frequency cap, a marketing opt-out) —
     * the caller must SEE that, or a journey silently loses a step. A null
     * return (stubbed client) reads as SENT. */
    enum SendOutcome { SENT, CAPPED, SUPPRESSED }

    default SendOutcome send(String partyId, String subject, String content) {
        return send(partyId, subject, content, java.util.Map.of());
    }

    /** Send inline copy with a personalization context ({{order.id}} etc.). */
    default SendOutcome send(String partyId, String subject, String content, java.util.Map<String, Object> context) {
        return send(partyId, subject, content, null, context);
    }

    /** Send inline copy on a named channel (inApp | email | sms | push | whatsapp);
     * null = in-app. A journey step's channel is honoured for inline copy exactly
     * as it is for a template — a "WhatsApp-first" journey must not fall back
     * to the inbox in silence. */
    default SendOutcome send(String partyId, String subject, String content, String channel,
            java.util.Map<String, Object> context) {
        return send(partyId, subject, content, channel, context, null);
    }

    /** Send inline copy that may have been written by a model. A non-null
     * {@code ai} rides beside the TMF681 payload as the characteristics
     * {@code aiGenerated=true} and {@code aiModel} (EU AI Act Art. 50(2):
     * machine-readable marking); the standard fields are untouched. */
    SendOutcome send(String partyId, String subject, String content, String channel,
            java.util.Map<String, Object> context, AiMark ai);

    /** Provenance of copy a model wrote: which model ("provider/model"), or
     * null when the console could not say. Absent (a null AiMark) = human copy. */
    record AiMark(String model) {
    }

    /** Send via a reusable template — communication renders the localized,
     *  tokenized copy for the given channel and context. */
    SendOutcome sendTemplated(String partyId, String templateRef, String locale, String channel,
            java.util.Map<String, Object> context);
}
