package com.devbrain.controller;

import com.devbrain.agent.parsers.ParserManager;
import com.devbrain.event.IngestionEvent;
import com.devbrain.event.IngestionProducer;
import com.devbrain.model.dto.IngestionRequest;
import com.devbrain.model.entity.KnowledgeNode;
import com.devbrain.model.neo4j.RelationType;
import com.devbrain.repository.KnowledgeNodeRepository;
import com.devbrain.service.KnowledgeService;
import com.devbrain.service.SourceDetector;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * REST API for knowledge management.
 * Handles content ingestion, search, and graph exploration.
 */
@RestController
@RequestMapping("/api/knowledge")
@CrossOrigin(origins = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.DELETE, RequestMethod.OPTIONS})
public class KnowledgeController {

    private final KnowledgeService knowledgeService;
    private final KnowledgeNodeRepository nodeRepository;
    private final IngestionProducer ingestionProducer;
    private final SourceDetector sourceDetector;
    private final ParserManager parserManager;

    public KnowledgeController(KnowledgeService knowledgeService,
                               KnowledgeNodeRepository nodeRepository,
                               IngestionProducer ingestionProducer,
                               SourceDetector sourceDetector,
                               ParserManager parserManager) {
        this.knowledgeService = knowledgeService;
        this.nodeRepository = nodeRepository;
        this.ingestionProducer = ingestionProducer;
        this.sourceDetector = sourceDetector;
        this.parserManager = parserManager;
    }

    /**
     * POST /api/knowledge/ingest
     * Enqueues content for async ingestion via Kafka. "type" is optional: leave it out (or send "auto")
     * and the content is inspected to pick the right parser.
     */
    @PostMapping("/ingest")
    public ResponseEntity<Map<String, Object>> ingest(@RequestBody IngestionRequest request) {
        if (request.getContent() == null || request.getContent().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Nothing to add: content is empty."));
        }
        boolean auto = request.getType() == null || request.getType().isBlank()
                || "auto".equalsIgnoreCase(request.getType());
        String type = auto ? sourceDetector.detect(request.getContent()) : request.getType();

        if (!parserManager.hasParser(type)) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "Can't process this kind of content yet (" + type + ").",
                    "type", type));
        }
        ingestionProducer.publish(IngestionEvent.builder()
                .type(type)
                .content(request.getContent())
                .title(request.getTitle())
                .build());
        return ResponseEntity.accepted().body(Map.of(
                "status", "accepted",
                "message", "Content queued for ingestion",
                "type", type,
                "detected", auto
        ));
    }

    /**
     * GET /api/knowledge/nodes
     * Returns all knowledge nodes, most recent first.
     */
    @GetMapping("/nodes")
    public ResponseEntity<List<KnowledgeNode>> getAllNodes() {
        return ResponseEntity.ok(knowledgeService.getAllNodes());
    }

    /**
     * GET /api/knowledge/nodes/{id}
     * Returns a single knowledge node by ID.
     */
    @GetMapping("/nodes/{id}")
    public ResponseEntity<KnowledgeNode> getNode(@PathVariable UUID id) {
        return nodeRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * DELETE /api/knowledge/nodes/{id}
     * Removes a source everywhere: its row, vectors and graph node/relations.
     */
    @DeleteMapping("/nodes/{id}")
    public ResponseEntity<Map<String, Object>> deleteNode(@PathVariable UUID id) {
        return knowledgeService.deleteNode(id.toString())
                ? ResponseEntity.ok(Map.of("deleted", true, "id", id.toString()))
                : ResponseEntity.notFound().build();
    }

    /**
     * GET /api/knowledge/search?q=...&limit=...
     * Semantic search across the knowledge base.
     */
    @GetMapping("/search")
    public ResponseEntity<List<Map<String, Object>>> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "5") int limit) {
        List<EmbeddingMatch<TextSegment>> matches = knowledgeService.search(q, limit);

        List<Map<String, Object>> results = matches.stream()
                .map(m -> {
                    Map<String, Object> result = new HashMap<>();
                    result.put("score", m.score());
                    result.put("nodeId", m.embedded().metadata().getString("nodeId"));
                    result.put("title", m.embedded().metadata().getString("title"));
                    result.put("source", m.embedded().metadata().getString("source"));
                    result.put("snippet", truncate(m.embedded().text(), 300));
                    return result;
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(results);
    }

    /**
     * POST /api/knowledge/reindex
     * Rebuilds all vectors and the graph from the stored nodes.
     */
    @PostMapping("/reindex")
    public ResponseEntity<Map<String, Object>> reindex() {
        return ResponseEntity.ok(knowledgeService.reindexAll());
    }

    /**
     * GET /api/knowledge/graph
     * Returns every node and SIMILAR edge, for the graph view.
     */
    @GetMapping("/graph")
    public ResponseEntity<Map<String, Object>> getGraph() {
        return ResponseEntity.ok(knowledgeService.getGraph());
    }

    /**
     * GET /api/knowledge/graph/{nodeId}
     * All typed relationships of one node.
     */
    @GetMapping("/graph/{nodeId}")
    public ResponseEntity<List<Map<String, Object>>> getRelatedNodes(@PathVariable String nodeId) {
        return ResponseEntity.ok(knowledgeService.getRelatedNodes(nodeId, null));
    }

    /**
     * GET /api/knowledge/graph/{nodeId}/type/{relationType}
     * Relationships of one node filtered by type (e.g. builds_upon).
     */
    @GetMapping("/graph/{nodeId}/type/{relationType}")
    public ResponseEntity<?> getRelatedByType(@PathVariable String nodeId, @PathVariable String relationType) {
        if (!RelationType.isKnown(relationType)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown relation type: " + relationType,
                    "known", knowledgeService.getRelationTypeInfo().stream().map(t -> t.get("code")).toList()));
        }
        return ResponseEntity.ok(knowledgeService.getRelatedNodes(nodeId, relationType.toLowerCase()));
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }
}
