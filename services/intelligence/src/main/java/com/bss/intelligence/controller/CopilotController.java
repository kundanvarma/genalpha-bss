package com.bss.intelligence.controller;

import com.bss.intelligence.api.ApiConstants;
import com.bss.intelligence.service.ChatIntent;
import com.bss.intelligence.service.CopilotReply;
import com.bss.intelligence.service.CopilotRequests.CopilotChatRequest;
import com.bss.intelligence.service.CopilotRequests.IntentAsk;
import com.bss.intelligence.service.CopilotRequests.KnowledgeAskRequest;
import com.bss.intelligence.service.CopilotRequests.NextBestOfferRequest;
import com.bss.intelligence.service.CopilotService;
import com.bss.intelligence.service.CustomerSummary;
import com.bss.intelligence.service.IntentDraft;
import com.bss.intelligence.service.KnowledgeAnswer;
import com.bss.intelligence.service.KnowledgeAskService;
import com.bss.intelligence.service.KnowledgeGapView;
import com.bss.intelligence.service.NextBestOffer;
import com.bss.intelligence.service.ProductCopilotService;
import com.bss.intelligence.service.QuoteNarrative;
import com.bss.intelligence.service.TicketReplyDraft;
import com.bss.intelligence.service.WrapUp;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping(ApiConstants.BASE_PATH)
public class CopilotController {

    private final CopilotService service;
    private final ProductCopilotService productCopilot;
    private final KnowledgeAskService knowledgeAsk;
    private final com.bss.intelligence.service.NextBestOfferService nextBestOffer;

    public CopilotController(CopilotService service, ProductCopilotService productCopilot,
            KnowledgeAskService knowledgeAsk,
            com.bss.intelligence.service.NextBestOfferService nextBestOffer) {
        this.service = service;
        this.productCopilot = productCopilot;
        this.knowledgeAsk = knowledgeAsk;
        this.nextBestOffer = nextBestOffer;
    }

    @PostMapping("/customerSummary")
    public ResponseEntity<CustomerSummary> summarize(@RequestBody JsonNode request) {
        return ResponseEntity.ok(service.summarizeCustomer(request));
    }

    /** Chat-to-create: the product owner talks, the copilot proposes, the
     * console applies on confirmation — the model never writes. */
    @PostMapping("/productCopilot")
    public ResponseEntity<CopilotReply> productCopilot(@RequestBody CopilotChatRequest request) {
        return ResponseEntity.ok(productCopilot.chat(request));
    }

    @PostMapping("/intentDraft")
    public ResponseEntity<IntentDraft> intentDraft(@RequestBody IntentAsk request) {
        return ResponseEntity.ok(service.draftIntent(request));
    }

    @PostMapping("/quoteNarrative")
    public ResponseEntity<QuoteNarrative> narrative(@RequestBody JsonNode request) {
        return ResponseEntity.ok(service.draftQuoteNarrative(request));
    }

    /** Live intent on a care chat: what the customer wants right now, with a reply to consider. */
    @PostMapping("/chatIntent")
    public ResponseEntity<ChatIntent> chatIntent(@RequestBody JsonNode request) {
        return ResponseEntity.ok(service.chatIntent(request));
    }

    /** After-call work: the interaction note drafted from what the call record shows. */
    @PostMapping("/wrapUp")
    public ResponseEntity<WrapUp> wrapUp(@RequestBody JsonNode request) {
        return ResponseEntity.ok(service.wrapUp(request));
    }

    @PostMapping("/ticketReply")
    public ResponseEntity<TicketReplyDraft> reply(@RequestBody JsonNode request) {
        return ResponseEntity.ok(service.draftTicketReply(request));
    }

    /** Next best offer: TMF680 candidates (interest-fused), the model
     * supplies only the WHY. */
    @PostMapping("/nextBestOffer")
    public ResponseEntity<NextBestOffer> nextBestOffer(@RequestBody NextBestOfferRequest request) {
        return ResponseEntity.ok(nextBestOffer.nextBestOffer(
                request.partyId() == null ? "" : request.partyId()));
    }

    /** Ask the knowledge base: retrieval with the ASKER's own token (their
     * audience, their answer), then a grounded synthesis with sources. */
    @PostMapping("/knowledgeAsk")
    public ResponseEntity<KnowledgeAnswer> knowledgeAsk(@RequestBody KnowledgeAskRequest request) {
        String question = request.question() == null ? "" : request.question();
        var auth = SecurityContextHolder.getContext().getAuthentication();
        String bearer = auth instanceof JwtAuthenticationToken jwt ? jwt.getToken().getTokenValue() : "";
        return ResponseEntity.ok(knowledgeAsk.ask(bearer, question, request.context()));
    }

    /** What people asked that no article answered — the content team's to-do list. */
    @org.springframework.web.bind.annotation.GetMapping("/knowledgeGaps")
    public ResponseEntity<List<KnowledgeGapView>> knowledgeGaps() {
        return ResponseEntity.ok(knowledgeAsk.gaps());
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/knowledgeGaps/{id}")
    public ResponseEntity<Void> dismissGap(@org.springframework.web.bind.annotation.PathVariable String id) {
        knowledgeAsk.dismiss(id);
        return ResponseEntity.noContent().build();
    }
}
