package com.bss.communication.controller;

import com.bss.communication.api.ApiConstants;
import com.bss.communication.api.PagedResult;
import com.bss.communication.dto.MessagePatch;
import com.bss.communication.dto.MessageView;
import com.bss.communication.dto.SendOutcome;
import com.bss.communication.dto.SendRequest;
import com.bss.communication.service.CommunicationMessageService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@Validated
@RequestMapping(ApiConstants.BASE_PATH + "/communicationMessage")
public class CommunicationMessageController {

    private final CommunicationMessageService service;

    public CommunicationMessageController(CommunicationMessageService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<MessageView>> list(
            @RequestParam(name = "offset", defaultValue = "0") @Min(0) int offset,
            @RequestParam(name = "limit", defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam Map<String, String> allParams) {
        Map<String, String> filters = new HashMap<>(allParams);
        filters.remove("offset");
        filters.remove("limit");
        PagedResult<MessageView> result = service.findAll(offset, limit, filters);
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(result.totalCount()))
                .header("X-Result-Count", String.valueOf(result.items().size()))
                .body(result.items());
    }

    @GetMapping("/{id}")
    public ResponseEntity<MessageView> getById(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.findById(id));
    }

    @PostMapping
    public ResponseEntity<SendOutcome> send(@RequestBody SendRequest dto) {
        SendOutcome created = service.send(dto);
        // a suppressed send has no href; the map path minted the literal "null"
        // for the Location header and the contract has always been that 201
        return ResponseEntity.created(URI.create(hrefOf(created))).body(created);
    }

    private static String hrefOf(SendOutcome outcome) {
        return outcome instanceof MessageView view && view.href() != null ? view.href() : "null";
    }

    @PatchMapping("/{id}")
    public ResponseEntity<MessageView> patch(@PathVariable("id") String id,
                                                     @RequestBody MessagePatch patch) {
        return ResponseEntity.ok(service.patch(id, patch));
    }
}
