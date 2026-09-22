package com.bss.intelligence.chat;

import com.bss.intelligence.api.ApiConstants;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import com.bss.intelligence.chat.ChatViews.AgentReply;
import com.bss.intelligence.chat.ChatViews.ChatEscalateRequest;
import com.bss.intelligence.chat.ChatViews.ChatMessageRequest;
import com.bss.intelligence.chat.ChatViews.ChatMessageView;
import com.bss.intelligence.chat.ChatViews.ChatSessionOpened;
import com.bss.intelligence.chat.ChatViews.ChatSessionView;
import com.bss.intelligence.chat.ChatViews.ChatTurn;
import com.bss.intelligence.chat.ChatViews.EscalationReceipt;

/**
 * Care chat, three faces:
 *  - /careChat/guest/** — the visitor's rail: no token, the session id is the
 *    capability, answers come from the public shelf only
 *  - /careChat/session/** — the customer's rail: SELF-scoped like /forYou
 *    (party = token subject), so the bot can only ever see the asker's data
 *  - /careChat/agent/** — the desk's rail (ticket:write): list, read, reply;
 *    an agent's first message flips the session to 'agent' and silences the bot
 */
@RestController
@RequestMapping(ApiConstants.BASE_PATH + "/careChat")
public class CareChatController {

    private final CareChatService chat;

    public CareChatController(CareChatService chat) {
        this.chat = chat;
    }

    private static String subject() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null ? null : a.getName();
    }

    /* ---------------- guest ---------------- */

    @PostMapping("/guest/session")
    public ChatSessionOpened guestOpen() {
        return new ChatSessionOpened(chat.open(null).getId());
    }

    @PostMapping("/guest/session/{id}/message")
    public ChatTurn guestMessage(@PathVariable String id,
            @RequestBody ChatMessageRequest body) {
        return chat.customerMessage(chat.require(id, null, false), body.textOrEmpty());
    }

    @GetMapping("/guest/session/{id}/messages")
    public List<ChatMessageView> guestMessages(@PathVariable String id) {
        return chat.transcript(chat.require(id, null, false));
    }

    @PostMapping("/guest/session/{id}/escalate")
    public EscalationReceipt guestEscalate(@PathVariable String id,
            @RequestBody(required = false) ChatEscalateRequest body) {
        return chat.escalate(chat.require(id, null, false),
                body == null ? null : body.contact());
    }

    /* ---------------- signed-in customer ---------------- */

    @PostMapping("/session")
    public ChatSessionOpened open() {
        return new ChatSessionOpened(chat.open(subject()).getId());
    }

    @PostMapping("/session/{id}/message")
    public ChatTurn message(@PathVariable String id,
            @RequestBody ChatMessageRequest body) {
        return chat.customerMessage(chat.require(id, subject(), false),
                body.textOrEmpty());
    }

    @GetMapping("/session/{id}/messages")
    public List<ChatMessageView> messages(@PathVariable String id) {
        return chat.transcript(chat.require(id, subject(), false));
    }

    @PostMapping("/session/{id}/escalate")
    public EscalationReceipt escalate(@PathVariable String id,
            @RequestBody(required = false) ChatEscalateRequest body) {
        return chat.escalate(chat.require(id, subject(), false),
                body == null ? null : body.contact());
    }

    /* ---------------- agent desk ---------------- */

    @GetMapping("/agent/sessions")
    public List<ChatSessionView> agentSessions() {
        return chat.openSessions();
    }

    @GetMapping("/agent/session/{id}/messages")
    public List<ChatMessageView> agentRead(@PathVariable String id) {
        return chat.transcript(chat.require(id, null, true));
    }

    @PostMapping("/agent/session/{id}/message")
    public ResponseEntity<AgentReply> agentReply(@PathVariable String id,
            @RequestBody ChatMessageRequest body) {
        return ResponseEntity.ok(chat.agentMessage(chat.require(id, null, true),
                body.textOrEmpty()));
    }
}
