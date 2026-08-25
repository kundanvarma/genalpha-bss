package com.bss.intelligence.sim;

import com.bss.intelligence.api.ApiConstants;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** The fleet's lent prior — an unnamed distribution, or nothing. */
@RestController
@RequestMapping(ApiConstants.BASE_PATH + "/elasticityBenchmark")
public class ElasticityBenchmarkController {

    private final ElasticityBenchmarkService benchmark;

    public ElasticityBenchmarkController(ElasticityBenchmarkService benchmark) {
        this.benchmark = benchmark;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> get() {
        return ResponseEntity.ok(benchmark.benchmark());
    }
}
