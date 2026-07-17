package com.bss.intelligence.controller;

import com.bss.intelligence.service.ProductAdvisorService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** The product owner's advisor: findings with their receipts, and the
 * adopt door that births a DRAFT — decisions stay human. */
@RestController
public class ProductAdvisorController {

    private final ProductAdvisorService advisor;

    public ProductAdvisorController(ProductAdvisorService advisor) {
        this.advisor = advisor;
    }

    @GetMapping("/advisor/v1/findings")
    public ResponseEntity<List<Map<String, Object>>> findings() {
        return ResponseEntity.ok(advisor.findings());
    }

    @PostMapping("/advisor/v1/adopt")
    public ResponseEntity<Map<String, Object>> adopt(@RequestBody Map<String, Object> proposal) {
        return ResponseEntity.ok(advisor.adopt(proposal));
    }
}
