package com.bss.cart.controller;

import com.bss.cart.api.ApiConstants;
import com.bss.cart.api.PagedResult;
import com.bss.cart.dto.CartPatch;
import com.bss.cart.dto.CartRequest;
import com.bss.cart.dto.CartView;
import com.bss.cart.service.ShoppingCartService;
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

/**
 * Guests operate anonymously with the cart id as their secret; the service
 * enforces ownership once a cart is claimed. No DELETE — carts check out,
 * get abandoned, or linger; they are the funnel's history.
 */
@RestController
@Validated
@RequestMapping(ApiConstants.BASE_PATH + "/shoppingCart")
public class ShoppingCartController {

    private final ShoppingCartService service;

    public ShoppingCartController(ShoppingCartService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<CartView>> list(
            @RequestParam(name = "offset", defaultValue = "0") @Min(0) int offset,
            @RequestParam(name = "limit", defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam Map<String, String> allParams) {
        Map<String, String> filters = new HashMap<>(allParams);
        filters.remove("offset");
        filters.remove("limit");
        PagedResult<CartView> result = service.findAll(offset, limit, filters);
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(result.totalCount()))
                .header("X-Result-Count", String.valueOf(result.items().size()))
                .body(result.items());
    }

    @GetMapping("/{id}")
    public ResponseEntity<CartView> getById(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.findById(id));
    }

    @PostMapping
    public ResponseEntity<CartView> create(@RequestBody(required = false) CartRequest dto) {
        CartView created = service.create(dto == null ? CartRequest.EMPTY : dto);
        return ResponseEntity.created(URI.create(created.href())).body(created);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<CartView> patch(@PathVariable("id") String id, @RequestBody CartPatch patch) {
        return ResponseEntity.ok(service.patch(id, patch));
    }
}
