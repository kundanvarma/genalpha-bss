package com.bss.ticket.controller;

import com.bss.ticket.api.ApiConstants;
import com.bss.ticket.api.PagedResult;
import com.bss.ticket.service.TroubleTicketService;
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

    public TroubleTicketController(TroubleTicketService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(
            @RequestParam(name = "offset", defaultValue = "0") @Min(0) int offset,
            @RequestParam(name = "limit", defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam Map<String, String> allParams) {
        Map<String, String> filters = new HashMap<>(allParams);
        filters.remove("offset");
        filters.remove("limit");
        PagedResult<Map<String, Object>> result = service.findAll(offset, limit, filters);
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(result.totalCount()))
                .header("X-Result-Count", String.valueOf(result.items().size()))
                .body(result.items());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getById(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.findById(id));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> dto) {
        Map<String, Object> created = service.create(dto);
        return ResponseEntity.created(URI.create(String.valueOf(created.get("href")))).body(created);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<Map<String, Object>> patch(@PathVariable("id") String id,
                                                     @RequestBody Map<String, Object> patch) {
        return ResponseEntity.ok(service.patch(id, patch));
    }
}
