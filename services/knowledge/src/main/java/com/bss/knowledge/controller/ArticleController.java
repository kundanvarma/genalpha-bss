package com.bss.knowledge.controller;

import com.bss.knowledge.api.ApiConstants;
import com.bss.knowledge.dto.ArticleRequest;
import com.bss.knowledge.dto.ArticleView;
import com.bss.knowledge.service.ArticleService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping(ApiConstants.BASE_PATH)
public class ArticleController {

    private final ArticleService service;

    public ArticleController(ArticleService service) {
        this.service = service;
    }

    @GetMapping("/article")
    public ResponseEntity<List<ArticleView>> find(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String audience,
            @RequestParam(required = false) String tag) {
        return ResponseEntity.ok(service.find(q, category, audience, tag));
    }

    /**
     * THE ANONYMOUS DOOR (#225). Published articles on the `public` shelf, and
     * nothing else — a separate path rather than a flag on /article, so the
     * authenticated listing cannot be coaxed into serving it and this one has
     * no audience parameter to widen.
     *
     * Empty today: no article carries the audience. The mechanism ships; what
     * appears through it stays an operator's decision, one article at a time.
     */
    @GetMapping("/publicArticle")
    public ResponseEntity<List<ArticleView>> findPublic(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String tag) {
        return ResponseEntity.ok(service.findPublic(q, category, tag));
    }

    @GetMapping("/article/{id}")
    public ResponseEntity<ArticleView> findById(@PathVariable String id) {
        return ResponseEntity.ok(service.findById(id));
    }

    @PostMapping("/article")
    public ResponseEntity<ArticleView> create(@RequestBody ArticleRequest dto) {
        ArticleView created = service.create(dto);
        return ResponseEntity.created(URI.create(created.href())).body(created);
    }

    @PatchMapping("/article/{id}")
    public ResponseEntity<ArticleView> patch(@PathVariable String id,
            @RequestBody ArticleRequest dto) {
        return ResponseEntity.ok(service.patch(id, dto));
    }

    @DeleteMapping("/article/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
