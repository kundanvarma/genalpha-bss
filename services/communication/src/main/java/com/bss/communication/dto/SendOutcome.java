package com.bss.communication.dto;

/**
 * A send has two honest answers: the message that was created, or the reason
 * nobody was contacted. A sealed pair, never one record with half its keys null.
 */
public sealed interface SendOutcome permits MessageView, SuppressedSend {
}
