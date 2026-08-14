package com.bss.communication.client;

import org.springframework.stereotype.Component;

/**
 * Routes a stored message to its channel's delivery seam. The in-app inbox is
 * always the record; email/sms/push are additional, fail-open deliveries that
 * ride on top. One place decides "how does this channel actually leave".
 */
@Component
public class ChannelDispatcher {

    private final EspForwarder esp;
    private final SmsForwarder sms;
    private final PushForwarder push;

    public ChannelDispatcher(EspForwarder esp, SmsForwarder sms, PushForwarder push) {
        this.esp = esp;
        this.sms = sms;
        this.push = push;
    }

    public void dispatch(String tenantId, String messageId, String partyId,
            String subject, String content, String channel) {
        String ch = channel == null ? "inApp" : channel;
        switch (ch) {
            case "sms" -> sms.forward(tenantId, messageId, partyId,
                    content == null || content.isBlank() ? subject : content);
            case "push" -> push.forward(tenantId, messageId, partyId, subject, content);
            // "email" and the "inApp" default both go through the ESP seam, which
            // itself gates on the tenant's delivery provider — so an ESP tenant
            // emails every message while an in-app tenant stays in the inbox.
            default -> esp.forward(tenantId, messageId, partyId, subject, content);
        }
    }
}
