package com.devbrain.service;

import com.devbrain.model.entity.KnowledgeNode;
import com.devbrain.model.neo4j.GraphNode;
import com.devbrain.repository.GraphNodeRepository;
import com.devbrain.repository.KnowledgeNodeRepository;
import tools.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Central service for knowledge management.
 * Handles dual storage: PostgreSQL (content + embeddings via pgvector) + Neo4j (relationships).
 * Auto-links new nodes to existing similar nodes.
 * Ported from Bits2Brain's KnowledgeService.
 */
@Slf4j
@Service
public class KnowledgeService {

    private final KnowledgeNodeRepository nodeRepository;
    private final GraphNodeRepository graphNodeRepository;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final ObjectMapper objectMapper;
    private final Neo4jClient neo4jClient;
    private final ChatModel chatModel;
    private final JdbcTemplate jdbc;

    @Value("${devbrain.knowledge.similarity-threshold:0.75}")
    private double similarityThreshold;

    public KnowledgeService(
            KnowledgeNodeRepository nodeRepository,
            GraphNodeRepository graphNodeRepository,
            EmbeddingModel embeddingModel,
            EmbeddingStore<TextSegment> embeddingStore,
            ObjectMapper objectMapper,
            Neo4jClient neo4jClient,
            ChatModel chatModel,
            JdbcTemplate jdbc) {
        this.nodeRepository = nodeRepository;
        this.graphNodeRepository = graphNodeRepository;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.objectMapper = objectMapper;
        this.neo4jClient = neo4jClient;
        this.chatModel = chatModel;
        this.jdbc = jdbc;
    }

    /**
     * Saves parsed content to both PostgreSQL and Neo4j, then auto-links.
     *
     * @param parsed  Map with keys: title, text/content, summary, source, extra, tags
     * @param autoLink whether to find and create relationships to similar nodes
     */
    public KnowledgeNode saveFromParsedResult(Map<String, Object> parsed, boolean autoLink) {
        String title = (String) parsed.getOrDefault("title", "Untitled");
        String text = (String) parsed.getOrDefault("text",
                parsed.getOrDefault("content", ""));
        String summary = (String) parsed.getOrDefault("summary", "");
        String source = (String) parsed.getOrDefault("source", "unknown");

        // 1. Save to PostgreSQL
        String metadataJson;
        try {
            Map<String, Object> extra = new HashMap<>();
            if (parsed.containsKey("extra")) {
                extra.putAll(asMap(parsed.get("extra")));
            }
            if (parsed.containsKey("tags")) {
                extra.put("tags", parsed.get("tags"));
            }
            if (parsed.containsKey("concepts")) {
                extra.put("concepts", parsed.get("concepts"));
            }
            metadataJson = objectMapper.writeValueAsString(extra);
        } catch (Exception e) {
            metadataJson = "{}";
        }

        KnowledgeNode node = KnowledgeNode.builder()
                .title(title)
                .content(text)
                .summary(summary)
                .source(source)
                .sourceUrl((String) parsed.get("sourceUrl"))
                .metadata(metadataJson)
                .build();

        node = nodeRepository.save(node);
        log.info("[KnowledgeService] Saved node: {} ({})", node.getTitle(), node.getId());

        // 2. Embeddings (pgvector) + 3. graph (Neo4j) + 4. auto-link
        indexNode(node, parsed.get("tags"), parsed.get("concepts"), autoLink);

        return node;
    }

    private static final int CHUNK_CHARS = 1000;
    private static final int CHUNK_OVERLAP = 150;
    private static final int MAX_CHUNKS = 150;
    private static final int MAX_SIMILAR_LINKS = 5;

    /**
     * Writes everything derived from a saved node: one "node" vector (title + summary + tags, used
     * for linking), many "chunk" vectors (used for retrieval), the Neo4j node, its concepts, and links.
     */
    private List<Map<String, Object>> indexNode(KnowledgeNode node, Object tags, Object concepts, boolean autoLink) {
        String id = node.getId().toString();
        List<String> terms = normalizeTerms(tags, concepts);

        String nodeText = node.getTitle() + "\n" + nullToEmpty(node.getSummary())
                + (terms.isEmpty() ? "" : "\n" + String.join(", ", terms));
        Embedding nodeEmbedding = embeddingModel.embed(nodeText).content();
        embeddingStore.add(nodeEmbedding, segment(nodeText, node, "node", 0));

        List<String> chunks = chunk(node.getContent());
        for (int i = 0; i < chunks.size(); i++) {
            String text = node.getTitle() + "\n" + chunks.get(i);
            embeddingStore.add(embeddingModel.embed(text).content(), segment(text, node, "chunk", i));
        }
        log.info("[KnowledgeService] Stored 1 node vector + {} chunk vectors for {}", chunks.size(), id);

        neo4jClient.query("""
                MERGE (n:Knowledge {uuid: $uuid})
                SET n.title = $title, n.source = $source, n.createdAt = $createdAt, n.extra = $extra
                """)
                .bindAll(Map.of("uuid", id, "title", node.getTitle(), "source", node.getSource(),
                        "createdAt", node.getCreatedAt().toString(), "extra", nullToEmpty(node.getMetadata())))
                .run();
        for (String term : terms) {
            neo4jClient.query("""
                    MATCH (n:Knowledge {uuid: $uuid})
                    MERGE (c:Concept {name: $name})
                    MERGE (n)-[:MENTIONS]->(c)
                    """)
                    .bindAll(Map.of("uuid", id, "name", term))
                    .run();
        }

        return autoLink ? linkNode(node, nodeEmbedding) : List.of();
    }

    private TextSegment segment(String text, KnowledgeNode node, String kind, int index) {
        return TextSegment.from(text, Metadata.from("nodeId", node.getId().toString())
                .put("title", node.getTitle())
                .put("source", node.getSource())
                .put("kind", kind)
                .put("chunk", index));
    }

    /** Fixed-size overlapping windows, cut at whitespace so words aren't split. */
    static List<String> chunk(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        String t = text.strip();
        int start = 0;
        while (start < t.length() && out.size() < MAX_CHUNKS) {
            int end = Math.min(start + CHUNK_CHARS, t.length());
            if (end < t.length()) {
                int ws = t.lastIndexOf(' ', end);
                if (ws > start + CHUNK_CHARS / 2) end = ws;
            }
            out.add(t.substring(start, end).strip());
            if (end >= t.length()) break;
            start = Math.max(end - CHUNK_OVERLAP, start + 1);
        }
        return out;
    }

    /** Lower-cased, de-duplicated tags + concepts; these become shared Concept nodes in the graph. */
    private static List<String> normalizeTerms(Object... lists) {
        Set<String> out = new LinkedHashSet<>();
        for (Object l : lists) {
            if (l instanceof Collection<?> c) {
                for (Object o : c) {
                    String t = String.valueOf(o).strip().toLowerCase();
                    if (t.length() >= 2 && t.length() <= 60) out.add(t);
                }
            }
        }
        return new ArrayList<>(out);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static final int MAX_CANDIDATES = 6;
    private static final double CANDIDATE_MIN_SCORE = 0.45;
    private static final double MIN_RELATION_STRENGTH = 0.5;

    /**
     * Two-stage relation building.
     * 1) Cheap recall: candidates are the nearest nodes by vector similarity plus nodes sharing a concept.
     * 2) Precise decision: an LLM judges each candidate pair and states WHY they are related. Only pairs
     *    about the same specific subject are linked, so generic overlap ("both are documentation") never links.
     */
    private List<Map<String, Object>> linkNode(KnowledgeNode node, Embedding nodeEmbedding) {
        String id = node.getId().toString();
        Map<String, KnowledgeNode> candidates = new LinkedHashMap<>();

        for (EmbeddingMatch<TextSegment> m : embeddingStore.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(nodeEmbedding).maxResults(100).minScore(CANDIDATE_MIN_SCORE).build()).matches()) {
            String other = m.embedded().metadata().getString("nodeId");
            if (!"node".equals(m.embedded().metadata().getString("kind")) || other == null || other.equals(id)) continue;
            if (candidates.size() >= MAX_CANDIDATES) break;
            findNode(other).ifPresent(n -> candidates.putIfAbsent(other, n));
        }
        for (Map<String, Object> row : neo4jClient.query("""
                MATCH (n:Knowledge {uuid: $id})-[:MENTIONS]->(:Concept)<-[:MENTIONS]-(m:Knowledge)
                WHERE m.uuid <> $id RETURN DISTINCT m.uuid AS id LIMIT 6
                """).bind(id).to("id").fetch().all()) {
            String other = String.valueOf(row.get("id"));
            if (candidates.size() >= MAX_CANDIDATES + 3) break;
            findNode(other).ifPresent(n -> candidates.putIfAbsent(other, n));
        }
        if (candidates.isEmpty()) {
            log.info("[KnowledgeService] No link candidates for {}", id);
            return List.of();
        }

        List<KnowledgeNode> list = new ArrayList<>(candidates.values());
        StringBuilder prompt = new StringBuilder(com.devbrain.util.Prompts.RELATION_JUDGE)
                .append("NEW DOCUMENT\n").append(describe(node)).append("\nCANDIDATES\n");
        for (int i = 0; i < list.size(); i++) {
            prompt.append("[").append(i + 1).append("] ").append(describe(list.get(i))).append("\n");
        }

        int linked = 0;
        List<Map<String, Object>> judgements = new ArrayList<>();
        try {
            String reply = chatModel.chat(prompt.toString());
            var verdicts = objectMapper.readValue(com.devbrain.util.JsonText.stripFences(reply),
                    new tools.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
            for (Map<String, Object> v : verdicts) {
                int idx = ((Number) v.getOrDefault("id", 0)).intValue() - 1;
                double strength = v.get("strength") instanceof Number n ? n.doubleValue() : 0;
                if (idx < 0 || idx >= list.size()) continue;
                boolean related = Boolean.TRUE.equals(v.get("related")) && strength >= MIN_RELATION_STRENGTH;
                Map<String, Object> j = new LinkedHashMap<>();
                j.put("document", node.getTitle());
                j.put("candidate", list.get(idx).getTitle());
                j.put("linked", related);
                j.put("strength", strength);
                j.put("reason", String.valueOf(v.getOrDefault("relation", "")));
                judgements.add(j);
                if (!related) continue;
                neo4jClient.query("""
                        MATCH (a:Knowledge {uuid: $from}), (b:Knowledge {uuid: $to})
                        MERGE (a)-[r:RELATED]->(b) SET r.strength = $strength, r.reason = $reason
                        """)
                        .bindAll(Map.of("from", id, "to", list.get(idx).getId().toString(),
                                "strength", strength, "reason", String.valueOf(v.getOrDefault("relation", ""))))
                        .run();
                linked++;
            }
        } catch (Exception e) {
            log.warn("[KnowledgeService] Relation judging failed for {}: {}", id, e.getMessage());
        }
        log.info("[KnowledgeService] Linked {} of {} candidates for {}", linked, list.size(), id);
        return judgements;
    }

    private String describe(KnowledgeNode n) {
        return n.getTitle() + " | " + nullToEmpty(n.getSummary()) + " | concepts: "
                + String.join(", ", normalizeTerms(parseMetadata(n.getMetadata()).get("concepts"),
                parseMetadata(n.getMetadata()).get("tags")));
    }

    /**
     * Chunk-level semantic search, best match per document. Used by the search endpoint.
     */
    public List<EmbeddingMatch<TextSegment>> search(String query, int maxResults) {
        Map<String, EmbeddingMatch<TextSegment>> best = new LinkedHashMap<>();
        for (EmbeddingMatch<TextSegment> m : searchChunks(query, maxResults * 6, 0.3)) {
            best.putIfAbsent(m.embedded().metadata().getString("nodeId"), m);
        }
        return best.values().stream().limit(maxResults).toList();
    }

    /**
     * Raw vector search over all stored vectors (node + chunk), best first.
     */
    public List<EmbeddingMatch<TextSegment>> searchChunks(String query, int maxResults, double minScore) {
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        return embeddingStore.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(maxResults)
                .minScore(minScore)
                .build())
                .matches();
    }

    public Optional<KnowledgeNode> findNode(String id) {
        try {
            return nodeRepository.findById(UUID.fromString(id));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * One-hop graph neighbours of the given nodes (excluding themselves), with why they are related.
     * Each row: id, title, kind ("related"), detail (why they are related).
     */
    public List<Map<String, Object>> neighbors(Collection<String> nodeIds) {
        if (nodeIds.isEmpty()) return List.of();
        return new ArrayList<>(neo4jClient.query("""
                MATCH (a:Knowledge)-[r:RELATED]-(b:Knowledge)
                WHERE a.uuid IN $ids AND NOT b.uuid IN $ids
                RETURN b.uuid AS id, b.title AS title, 'related' AS kind, r.reason AS detail
                ORDER BY r.strength DESC
                """).bind(new ArrayList<>(nodeIds)).to("ids").fetch().all());
    }

    /**
     * Get all knowledge nodes.
     */
    public List<KnowledgeNode> getAllNodes() {
        return nodeRepository.findAllByOrderByCreatedAtDesc();
    }

    /**
     * Get graph neighbors of a node.
     */
    public List<GraphNode> getRelatedNodes(String nodeId) {
        return graphNodeRepository.findSimilarNodes(nodeId);
    }

    /**
     * Whole graph for visualization: knowledge nodes and RELATED edges (each with the reason).
     */
    public Map<String, Object> getGraph() {
        Collection<Map<String, Object>> nodes = neo4jClient
                .query("MATCH (n:Knowledge) RETURN n.uuid AS id, n.title AS title, n.source AS source")
                .fetch().all();
        Collection<Map<String, Object>> edges = neo4jClient.query("""
                MATCH (a:Knowledge)-[r:RELATED]->(b:Knowledge)
                RETURN a.uuid AS from, b.uuid AS to, 'related' AS kind, r.strength AS score, r.reason AS via
                """).fetch().all();
        return Map.of("nodes", nodes, "edges", edges);
    }

    /**
     * Removes a source from every store: the Neo4j node (with its relations), its vectors,
     * then the PostgreSQL row. Concepts left with no documents are dropped too.
     */
    public boolean deleteNode(String id) {
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (!nodeRepository.existsById(uuid)) return false;
        neo4jClient.query("MATCH (n:Knowledge {uuid: $id}) DETACH DELETE n").bind(id).to("id").run();
        neo4jClient.query("MATCH (c:Concept) WHERE NOT (c)<-[:MENTIONS]-() DELETE c").run();
        int vectors = jdbc.update("DELETE FROM embeddings WHERE metadata->>'nodeId' = ?", id);
        nodeRepository.deleteById(uuid);
        log.info("[KnowledgeService] Deleted node {} ({} vectors)", id, vectors);
        return true;
    }

    /**
     * Rebuilds every vector and the whole graph from the nodes stored in PostgreSQL.
     * Use after changing how nodes are indexed (chunking, thresholds, concepts).
     */
    public Map<String, Object> reindexAll() {
        embeddingStore.removeAll();
        neo4jClient.query("MATCH (n) DETACH DELETE n").run();
        List<KnowledgeNode> all = nodeRepository.findAllByOrderByCreatedAtDesc();
        Collections.reverse(all); // oldest first, so links form in ingestion order
        List<Map<String, Object>> judgements = new ArrayList<>();
        for (KnowledgeNode n : all) {
            Map<String, Object> meta = parseMetadata(n.getMetadata());
            judgements.addAll(indexNode(n, meta.get("tags"), meta.get("concepts"), true));
        }
        log.info("[KnowledgeService] Reindexed {} nodes", all.size());
        return Map.of("reindexed", all.size(), "judgements", judgements);
    }

    private Map<String, Object> parseMetadata(String json) {
        try {
            return json == null || json.isBlank() ? new HashMap<>()
                    : objectMapper.readValue(json, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object obj) {
        if (obj instanceof Map) return (Map<String, Object>) obj;
        return new HashMap<>();
    }
}
