package com.bss.campaign.client;

/** The delivery channel: campaign messages go out as TMF681 communications. */
public interface CommunicationClient {

    void send(String partyId, String subject, String content);
}
