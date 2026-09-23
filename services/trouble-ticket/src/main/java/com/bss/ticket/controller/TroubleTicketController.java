package com.bss.ticket.controller;

import com.bss.ticket.api.ApiConstants;
import com.bss.ticket.api.PagedResult;
import com.bss.ticket.dto.TicketView;
import com.bss.ticket.dto.TroubleTicketCreateRequest;
import com.bss.ticket.dto.TroubleTicketPatchRequest;
import com.bss.ticket.service.TroubleTicketService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

/** No DELETE: tickets are the support history — they close, they do not vanish. */
@RestController
@Validated
@RequestMapping(ApiConstants.BASE_PATH + "/troubleTicket")
public class TroubleTicketController {

    private final TroubleTicketService service;
    private final ObjectMapper objectMapper;

    public TroubleTicketController(TroubleTicketService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public ResponseEntity<List<TicketView>> list(
            @RequestParam(name = "offset", defaultValue = "0") @Min(0) int offset,
            @RequestParam(name = "limit", defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam Map<String, String> allParams) {
        Map<String, String> filters = new HashMap<>(allParams);
        filters.remove("offset");
        filters.remove("limit");
        filters.remove("fields");
        PagedResult<TicketView> result = service.findAll(offset, limit, filters);
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(result.totalCount()))
                .header("X-Result-Count", String.valueOf(result.items().size()))
                .body(result.items());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Object> getById(@PathVariable("id") String id,
            @RequestParam(name = "fields", required = false) String fields) {
        TicketView full = service.findById(id);
        if (fields == null || fields.isBlank()) {
            return ResponseEntity.ok(full);
        }
        // TMF630 attribute selection, strict: exactly the asked-for fields, in
        // the order asked for. The projection walks the record's own tree, so
        // a key the view leaves off (relatedParty, relatedEntity) is missing
        // here too, exactly as the map's containsKey used to decide.
        JsonNode tree = objectMapper.valueToTree(full);
        ObjectNode slim = objectMapper.createObjectNode();
        for (String f : fields.split(",")) {
            String key = f.trim();
            if (tree.has(key)) {
                slim.set(key, tree.get(key));
            }
        }
        return ResponseEntity.ok(slim);
    }

    @PostMapping({"", "/"})
    public ResponseEntity<TicketView> create(@RequestBody TroubleTicketCreateRequest dto) {
        TicketView created = service.create(dto);
        return ResponseEntity.created(URI.create(created.href())).body(created);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<TicketView> patch(@PathVariable("id") String id,
                                            @RequestBody TroubleTicketPatchRequest patch) {
        return ResponseEntity.ok(service.patch(id, patch));
    }
}
