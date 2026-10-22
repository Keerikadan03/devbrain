package com.devbrain.service;

import com.devbrain.model.entity.KnowledgeNode;
import com.devbrain.model.neo4j.RelationType;
import com.devbrain.repository.GraphNodeRepository;
import com.devbrain.repository.KnowledgeNodeRepository;
import tools.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
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
    private final RelationshipClassifier relationshipClassifier;
    private final JdbcTemplate jdbc;

    /** Minimum node-vector similarity for a document to be considered as a link candidate (small graphs). */
    @Value("${devbrain.knowledge.similarity-threshold:0.45}")
    private double baseSimilarityThreshold;

    /** Upper bound on candidates judged per new document, i.e. on relationships it can get at ingest. */
    @Value("${devbrain.knowledge.max-relationships-per-node:15}")
    private int maxRelationshipsPerNode;

    /** same_concept edges at or above this strength put their nodes in one visual cluster. */
    @Value("${devbrain.knowledge.cluster-threshold:0.8}")
    private double clusterThreshold;

    public KnowledgeService(
            KnowledgeNodeRepository nodeRepository,
            GraphNodeRepository graphNodeRepository,
            EmbeddingModel embeddingModel,
            EmbeddingStore<TextSegment> embeddingStore,
            ObjectMapper objectMapper,
            Neo4jClient neo4jClient,
            RelationshipClassifier relationshipClassifier,
            JdbcTemplate jdbc) {
        this.nodeRepository = nodeRepository;
        this.graphNodeRepository = graphNodeRepository;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.objectMapper = objectMapper;
        this.neo4jClient = neo4jClient;
        this.relationshipClassifier = relationshipClassifier;
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
                SET n.title = $title, n.source = $source, n.summary = $summary, n.createdAt = $createdAt, n.extra = $extra
                """)
                .bindAll(Map.of("uuid", id, "title", node.getTitle(), "source", node.getSource(),
                        "summary", nullToEmpty(node.getSummary()),
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

    private static final double MIN_RELATION_STRENGTH = 0.5;

    /**
     * Two-stage relationship building.
     * 1) Recall: nearest documents by node vector (dynamic threshold) plus documents sharing a concept.
     * 2) Precision: RelationshipClassifier decides which are genuinely related and how (8 typed relations,
     *    with direction and an explanation). Stored as KNOWLEDGE_REL edges.
     * Returns every verdict (linked or not) so callers can show why pairs were or weren't linked.
     */
    private List<Map<String, Object>> linkNode(KnowledgeNode node, Embedding nodeEmbedding) {
        String id = node.getId().toString();
        Map<String, KnowledgeNode> candidates = new LinkedHashMap<>();
        double threshold = computeDynamicThreshold();

        for (EmbeddingMatch<TextSegment> m : embeddingStore.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(nodeEmbedding).maxResults(maxRelationshipsPerNode * 10).minScore(threshold).build()).matches()) {
            String other = m.embedded().metadata().getString("nodeId");
            if (!"node".equals(m.embedded().metadata().getString("kind")) || other == null || other.equals(id)) continue;
            if (candidates.size() >= maxRelationshipsPerNode) break;
            findNode(other).ifPresent(n -> candidates.putIfAbsent(other, n));
        }
        for (Map<String, Object> row : neo4jClient.query("""
                MATCH (n:Knowledge {uuid: $id})-[:MENTIONS]->(c:Concept)<-[:MENTIONS]-(m:Knowledge)
                WHERE m.uuid <> $id
                RETURN m.uuid AS id, count(DISTINCT c) AS shared ORDER BY shared DESC
                """).bind(id).to("id").fetch().all()) {
            if (candidates.size() >= maxRelationshipsPerNode) break;
            String other = String.valueOf(row.get("id"));
            findNode(other).ifPresent(n -> candidates.putIfAbsent(other, n));
        }
        if (candidates.isEmpty()) {
            log.info("[KnowledgeService] No link candidates for {}", id);
            return List.of();
        }

        List<KnowledgeNode> list = new ArrayList<>(candidates.values());
        List<Map<String, Object>> judgements = new ArrayList<>();
        int linked = 0;
        for (RelationshipClassifier.Classification c : relationshipClassifier.classify(node, list, this::describe)) {
            KnowledgeNode other = list.get(c.candidateIndex());
            boolean link = c.related() && c.strength() >= MIN_RELATION_STRENGTH;

            Map<String, Object> j = new LinkedHashMap<>();
            j.put("document", node.getTitle());
            j.put("candidate", other.getTitle());
            j.put("linked", link);
            j.put("type", link ? c.type().getCode() : "");
            j.put("strength", c.strength());
            j.put("explanation", c.explanation());
            judgements.add(j);
            if (!link) continue;

            RelationType type = c.type();
            // Directional edges point FROM -> TO ("FROM builds upon TO"); symmetric ones keep new -> candidate.
            boolean newFirst = !type.isDirectional() || c.newIsSource();
            String from = newFirst ? id : other.getId().toString();
            String to = newFirst ? other.getId().toString() : id;
            createTypedRelationship(from, to, type, c.strength(), c.explanation());
            linked++;
        }
        log.info("[KnowledgeService] Linked {} of {} candidates for {} (threshold {})", linked, list.size(), id, threshold);
        return judgements;
    }

    /** Replaces any existing KNOWLEDGE_REL between the pair (either direction) with the new classification. */
    private void createTypedRelationship(String from, String to, RelationType type, double score, String explanation) {
        neo4jClient.query("""
                MATCH (a:Knowledge {uuid: $from})
                MATCH (b:Knowledge {uuid: $to})
                OPTIONAL MATCH (a)-[old:KNOWLEDGE_REL]-(b)
                DELETE old
                WITH DISTINCT a, b
                CREATE (a)-[:KNOWLEDGE_REL {score: $score, relationType: $type, color: $color,
                                            directional: $directional, explanation: $explanation}]->(b)
                """)
                .bindAll(Map.of("from", from, "to", to, "score", score, "type", type.getCode(),
                        "color", type.getColor(), "directional", type.isDirectional(), "explanation", explanation))
                .run();
    }

    /**
     * Candidate cutoff rises with graph size: small graphs cast a wider net, large ones stay focused.
     * <20 nodes: base; <100: base + 0.05; 100+: base + 0.10 (capped at 0.85).
     */
    private double computeDynamicThreshold() {
        long count = nodeRepository.count();
        if (count < 20) return baseSimilarityThreshold;
        if (count < 100) return Math.min(baseSimilarityThreshold + 0.05, 0.80);
        return Math.min(baseSimilarityThreshold + 0.10, 0.85);
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
     * Each row: id, title, kind (relationship type label), detail (the classifier's explanation).
     */
    public List<Map<String, Object>> neighbors(Collection<String> nodeIds) {
        if (nodeIds.isEmpty()) return List.of();
        return new ArrayList<>(neo4jClient.query("""
                MATCH (a:Knowledge)-[r:KNOWLEDGE_REL]-(b:Knowledge)
                WHERE a.uuid IN $ids AND NOT b.uuid IN $ids
                RETURN b.uuid AS id, b.title AS title, replace(r.relationType, '_', ' ') AS kind,
                       r.explanation AS detail
                ORDER BY r.score DESC
                """).bind(new ArrayList<>(nodeIds)).to("ids").fetch().all());
    }

    /**
     * Get all knowledge nodes.
     */
    public List<KnowledgeNode> getAllNodes() {
        return nodeRepository.findAllByOrderByCreatedAtDesc();
    }

    /**
     * Typed relationships of one node, optionally filtered by type (null = all). Each row says which
     * way the edge points relative to this node ("outgoing": this node is the FROM side).
     */
    public List<Map<String, Object>> getRelatedNodes(String nodeId, String relationType) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", nodeId);
        params.put("type", relationType);
        return new ArrayList<>(neo4jClient.query("""
                MATCH (n:Knowledge {uuid: $id})-[r:KNOWLEDGE_REL]-(m:Knowledge)
                WHERE $type IS NULL OR r.relationType = $type
                RETURN m.uuid AS id, m.title AS title, m.source AS source, r.relationType AS relationType,
                       r.color AS color, r.directional AS directional, r.score AS score, r.explanation AS explanation,
                       CASE WHEN startNode(r) = n THEN 'outgoing' ELSE 'incoming' END AS direction
                ORDER BY r.score DESC
                """).bindAll(params).fetch().all());
    }

    /**
     * Whole graph for visualization:
     * { nodes: [{id,title,source,summary,createdAt,connectionCount}],
     *   edges: [{source,target,score,relationType,color,directional,explanation}],
     *   clusters: [{label,nodeIds,size}], relationTypes: [{code,color,directional,description,label}] }
     * connectionCount is computed here rather than stored, so it can never go stale after deletes.
     */
    public Map<String, Object> getGraph() {
        List<Map<String, Object>> nodes = new ArrayList<>(neo4jClient.query("""
                MATCH (n:Knowledge)
                OPTIONAL MATCH (n)-[r:KNOWLEDGE_REL]-()
                RETURN n.uuid AS id, n.title AS title, n.source AS source, n.summary AS summary,
                       n.createdAt AS createdAt, count(r) AS connectionCount
                """).fetch().all());
        List<Map<String, Object>> edges = new ArrayList<>(neo4jClient.query("""
                MATCH (a:Knowledge)-[r:KNOWLEDGE_REL]->(b:Knowledge)
                RETURN a.uuid AS source, b.uuid AS target, r.score AS score, r.relationType AS relationType,
                       r.color AS color, r.directional AS directional, r.explanation AS explanation
                """).fetch().all());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodes", nodes);
        result.put("edges", edges);
        result.put("clusters", computeClusters(nodes, edges));
        result.put("relationTypes", getRelationTypeInfo());
        return result;
    }

    /**
     * Union-find over strong same_concept edges: nodes about the same thing form one cluster.
     */
    private List<Map<String, Object>> computeClusters(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        Map<String, String> parent = new HashMap<>();
        Map<String, String> titles = new HashMap<>();
        Map<String, Long> degree = new HashMap<>();
        for (Map<String, Object> n : nodes) {
            String id = (String) n.get("id");
            parent.put(id, id);
            titles.put(id, (String) n.get("title"));
            degree.put(id, n.get("connectionCount") instanceof Number c ? c.longValue() : 0L);
        }
        for (Map<String, Object> e : edges) {
            double score = e.get("score") instanceof Number n ? n.doubleValue() : 0;
            if (RelationType.SAME_CONCEPT.getCode().equals(e.get("relationType")) && score >= clusterThreshold) {
                union(parent, (String) e.get("source"), (String) e.get("target"));
            }
        }
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (String id : parent.keySet()) groups.computeIfAbsent(find(parent, id), k -> new ArrayList<>()).add(id);

        List<Map<String, Object>> clusters = new ArrayList<>();
        for (List<String> members : groups.values()) {
            if (members.size() < 2) continue;
            // Label a cluster after its best-connected member.
            String hub = members.stream().max(Comparator.comparingLong(m -> degree.getOrDefault(m, 0L))).orElse(members.get(0));
            clusters.add(Map.of("label", String.valueOf(titles.get(hub)), "nodeIds", members, "size", members.size()));
        }
        return clusters;
    }

    private static String find(Map<String, String> parent, String x) {
        while (!parent.get(x).equals(x)) {
            parent.put(x, parent.get(parent.get(x)));
            x = parent.get(x);
        }
        return x;
    }

    private static void union(Map<String, String> parent, String a, String b) {
        if (!parent.containsKey(a) || !parent.containsKey(b)) return;
        String ra = find(parent, a), rb = find(parent, b);
        if (!ra.equals(rb)) parent.put(ra, rb);
    }

    /** Legend metadata for all relationship types. */
    public List<Map<String, Object>> getRelationTypeInfo() {
        List<Map<String, Object>> types = new ArrayList<>();
        for (RelationType rt : RelationType.values()) {
            types.add(Map.of("code", rt.getCode(), "color", rt.getColor(), "directional", rt.isDirectional(),
                    "description", rt.getDescription(), "label", rt.label()));
        }
        return types;
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
