package com.devbrain.service;

import com.devbrain.model.entity.KnowledgeNode;
import com.devbrain.repository.KnowledgeNodeRepository;
import com.devbrain.util.JsonText;
import com.devbrain.util.Prompts;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Groups documents into a hierarchy of named topics for the graph view.
 *
 * Structure: connected components of the relationship graph are the broadest topics; inside them,
 * Leiden community detection (Neo4j GDS, weighted by relationship type and strength) gives nested
 * sub-topics. The tree is then simplified so every level earns its place:
 *   - groups with fewer than MIN_GROUP documents dissolve into their parent,
 *   - a group whose only content is a single sub-group is replaced by that sub-group,
 *   - a group of at most SMALL_GROUP documents does not get sub-groups (it opens straight to documents).
 *
 * Levels are nesting height: a document is level 0, a group of documents is level 1, a group that
 * contains a level-1 group is level 2, and so on.
 *
 * Names: first from the documents' extracted concepts (the concept shared by the most members, rarer
 * concepts preferred on ties, never repeating an ancestor's or sibling's name). If enabled, one batched
 * LLM call then rewrites names into readable topics, only for groups whose membership changed (names are
 * cached by membership), and the concept names remain the fallback if the call fails.
 *
 * Recomputed in the background a few seconds after the graph changes (see markDirty).
 */
@Slf4j
@Service
public class TopicHierarchyService {

    private static final int MIN_GROUP = 3;
    private static final int SMALL_GROUP = 5;
    private static final double MIN_SHARED = 0.5; // a name must be shared by at least half the members

    private final Neo4jClient neo4jClient;
    private final KnowledgeNodeRepository nodeRepository;
    private final ObjectMapper objectMapper;
    private final ChatModel chatModel;

    @Value("${devbrain.topics.llm-names:true}")
    private boolean llmNames;

    /** LLM-chosen names keyed by group id (which is derived from membership). */
    private final Map<String, String> nameCache = new HashMap<>();

    private final AtomicReference<Map<String, Object>> cache = new AtomicReference<>();
    private volatile boolean dirty = true;

    public TopicHierarchyService(Neo4jClient neo4jClient, KnowledgeNodeRepository nodeRepository,
                                 ObjectMapper objectMapper, ChatModel chatModel) {
        this.neo4jClient = neo4jClient;
        this.nodeRepository = nodeRepository;
        this.objectMapper = objectMapper;
        this.chatModel = chatModel;
    }

    /** Call after anything that changes nodes or relationships; the next background pass recomputes. */
    public void markDirty() {
        dirty = true;
    }

    public Map<String, Object> get() {
        Map<String, Object> current = cache.get();
        if (current == null) {
            refresh();
            current = cache.get();
        }
        return current;
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public void refreshIfDirty() {
        if (dirty) refresh();
    }

    private synchronized void refresh() {
        dirty = false;
        try {
            cache.set(compute());
        } catch (Exception e) {
            log.warn("[TopicHierarchy] Recompute failed: {}", e.getMessage());
            if (cache.get() == null) cache.set(empty());
        }
    }

    // ---------------------------------------------------------------- tree model

    private static final class Group {
        final String key;
        Group parent;
        final List<Group> children = new ArrayList<>();
        final List<String> docs = new ArrayList<>(); // direct member documents
        int level;
        String name;
        List<String> terms = List.of();
        String id;

        Group(String key) {
            this.key = key;
        }

        List<String> allDocs() {
            List<String> all = new ArrayList<>(docs);
            for (Group c : children) all.addAll(c.allDocs());
            return all;
        }
    }

    // ---------------------------------------------------------------- compute

    private Map<String, Object> compute() {
        List<KnowledgeNode> knowledge = nodeRepository.findAll();
        if (knowledge.isEmpty()) return empty();
        Map<String, KnowledgeNode> byId = new HashMap<>();
        for (KnowledgeNode n : knowledge) byId.put(n.getId().toString(), n);

        List<String[]> edges = neo4jClient.query("""
                MATCH (a:Knowledge)-[:KNOWLEDGE_REL]->(b:Knowledge) RETURN a.uuid AS a, b.uuid AS b
                """).fetch().all().stream()
                .map(r -> new String[]{String.valueOf(r.get("a")), String.valueOf(r.get("b"))})
                .filter(e -> byId.containsKey(e[0]) && byId.containsKey(e[1]))
                .toList();

        Map<String, String> component = connectedComponents(byId.keySet(), edges);
        Map<String, List<Long>> communities = leidenCommunities();

        // Path from broadest to finest: component, then Leiden levels coarsest -> finest.
        Group root = new Group("root");
        Map<String, Group> groups = new HashMap<>();
        for (String doc : byId.keySet()) {
            List<String> path = new ArrayList<>();
            path.add("cc" + component.get(doc));
            List<Long> levels = communities.getOrDefault(doc, List.of());
            for (int i = levels.size() - 1; i >= 0; i--) path.add("L" + i + ":" + levels.get(i));

            Group parent = root;
            StringBuilder key = new StringBuilder();
            for (String step : path) {
                key.append('/').append(step); // full path keeps nesting strict even if ids repeat across parents
                Group p = parent;
                Group g = groups.computeIfAbsent(key.toString(), k -> {
                    Group created = new Group(k);
                    created.parent = p;
                    p.children.add(created);
                    return created;
                });
                parent = g;
            }
            parent.docs.add(doc);
        }

        simplify(root);
        for (Group top : root.children) assignLevels(top);

        Map<String, Map<String, String>> terms = termsByDoc(knowledge);
        Map<String, Integer> degree = new HashMap<>();
        for (String[] e : edges) {
            degree.merge(e[0], 1, Integer::sum);
            degree.merge(e[1], 1, Integer::sum);
        }
        Map<String, Integer> corpusDf = new HashMap<>();
        for (Map<String, String> t : terms.values()) for (String k : t.keySet()) corpusDf.merge(k, 1, Integer::sum);
        nameChildren(root, terms, corpusDf, byId.size(), byId, degree, Set.of());
        for (Group top : root.children) assignIds(top);
        String nameSource = llmNames ? applyLlmNames(root, byId) : "concepts";

        List<Map<String, Object>> clusters = new ArrayList<>();
        Map<String, String> docParent = new HashMap<>();
        for (Group top : root.children) flatten(top, null, clusters, docParent);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("clusters", clusters);
        out.put("rootClusterIds", root.children.stream().map(g -> g.id).toList());
        out.put("rootDocIds", root.docs);
        out.put("docParent", docParent);
        out.put("algorithm", communities.isEmpty() ? "connected-components" : "leiden");
        out.put("nameSource", nameSource);
        out.put("computedAt", Instant.now().toString());
        log.info("[TopicHierarchy] {} documents -> {} groups ({} top-level), {} ungrouped",
                byId.size(), clusters.size(), root.children.size(), root.docs.size());
        return out;
    }

    /** Runs hierarchical Leiden; returns each document's community ids, finest first. Empty if GDS fails. */
    private Map<String, List<Long>> leidenCommunities() {
        String graph = "devbrain-topics-" + System.nanoTime();
        Map<String, List<Long>> out = new HashMap<>();
        try {
            neo4jClient.query("""
                    MATCH (a:Knowledge)
                    OPTIONAL MATCH (a)-[r:KNOWLEDGE_REL]->(b:Knowledge)
                    WITH a, b, CASE r.relationType
                            WHEN 'same_concept' THEN 1.0 WHEN 'example_of' THEN 0.9 WHEN 'builds_upon' THEN 0.8
                            WHEN 'supersedes' THEN 0.8 WHEN 'depends_on' THEN 0.8 WHEN 'opposing_view' THEN 0.7
                            WHEN 'contrasts_with' THEN 0.6 ELSE 0.4 END * coalesce(r.score, 0.5) AS w
                    WITH gds.graph.project($graph, a, b, {relationshipProperties: {weight: w}},
                                           {undirectedRelationshipTypes: ['*']}) AS g
                    RETURN g.nodeCount AS nodes
                    """).bind(graph).to("graph").run();
            for (Map<String, Object> row : neo4jClient.query("""
                    CALL gds.leiden.stream($graph, {relationshipWeightProperty: 'weight',
                         includeIntermediateCommunities: true, randomSeed: 42, concurrency: 1})
                    YIELD nodeId, intermediateCommunityIds
                    RETURN gds.util.asNode(nodeId).uuid AS id, intermediateCommunityIds AS levels
                    """).bind(graph).to("graph").fetch().all()) {
                List<Long> levels = new ArrayList<>();
                if (row.get("levels") instanceof Collection<?> c) {
                    for (Object o : c) levels.add(((Number) o).longValue());
                }
                out.put(String.valueOf(row.get("id")), levels);
            }
        } catch (Exception e) {
            log.warn("[TopicHierarchy] Leiden unavailable, using connected components only: {}", e.getMessage());
            out.clear();
        } finally {
            try {
                neo4jClient.query("CALL gds.graph.drop($graph, false) YIELD graphName RETURN graphName")
                        .bind(graph).to("graph").run();
            } catch (Exception ignored) {
                // projection may not exist if projecting failed
            }
        }
        return out;
    }

    private static Map<String, String> connectedComponents(Set<String> docs, List<String[]> edges) {
        Map<String, String> parent = new HashMap<>();
        for (String d : docs) parent.put(d, d);
        for (String[] e : edges) {
            String ra = find(parent, e[0]), rb = find(parent, e[1]);
            if (!ra.equals(rb)) parent.put(ra, rb);
        }
        Map<String, String> out = new HashMap<>();
        for (String d : docs) out.put(d, find(parent, d));
        return out;
    }

    private static String find(Map<String, String> parent, String x) {
        while (!parent.get(x).equals(x)) {
            parent.put(x, parent.get(parent.get(x)));
            x = parent.get(x);
        }
        return x;
    }

    // ---------------------------------------------------------------- simplification

    /** Bottom-up clean-up so that every remaining level is meaningful (see class comment). */
    private static void simplify(Group g) {
        for (Group c : new ArrayList<>(g.children)) simplify(c);

        // Dissolve groups too small to be worth a bubble: their documents move up.
        for (Group c : new ArrayList<>(g.children)) {
            if (c.allDocs().size() < MIN_GROUP) {
                g.children.remove(c);
                g.docs.addAll(c.allDocs());
            }
        }
        // Replace a child that only wraps a single sub-group (or nothing but documents re-wrapped) with its content.
        for (Group c : new ArrayList<>(g.children)) {
            if (c.docs.isEmpty() && c.children.size() == 1) {
                Group only = c.children.get(0);
                int at = g.children.indexOf(c);
                g.children.set(at, only);
                only.parent = g;
            }
        }
        // Small groups open straight to their documents.
        if (g.parent != null && g.allDocs().size() <= SMALL_GROUP && !g.children.isEmpty()) {
            List<String> all = g.allDocs();
            g.children.clear();
            g.docs.clear();
            g.docs.addAll(all);
        }
    }

    private static int assignLevels(Group g) {
        int height = 1;
        for (Group c : g.children) height = Math.max(height, assignLevels(c) + 1);
        g.level = height;
        return height;
    }

    // ---------------------------------------------------------------- naming (c-TF-IDF over concepts)

    /** Per document: lower-cased term -> original spelling, concepts first, tags as fallback. */
    private Map<String, Map<String, String>> termsByDoc(List<KnowledgeNode> nodes) {
        Map<String, Map<String, String>> out = new HashMap<>();
        for (KnowledgeNode n : nodes) {
            Map<String, String> t = new LinkedHashMap<>();
            Map<String, Object> meta = parseMetadata(n.getMetadata());
            for (String field : List.of("concepts", "tags")) {
                if (meta.get(field) instanceof Collection<?> c) {
                    for (Object o : c) {
                        String s = String.valueOf(o).strip();
                        if (s.length() >= 2 && s.length() <= 60) t.putIfAbsent(s.toLowerCase(), s);
                    }
                }
            }
            out.put(n.getId().toString(), t);
        }
        return out;
    }

    /**
     * Names each child of {@code parent} by the concept shared by the most of its documents
     * (tf^2 x corpus idf: coverage first, rarer terms preferred on ties). Terms used by an ancestor
     * or an earlier sibling are skipped so names stay distinct.
     */
    private void nameChildren(Group parent, Map<String, Map<String, String>> terms, Map<String, Integer> corpusDf,
                              int corpusSize, Map<String, KnowledgeNode> byId, Map<String, Integer> degree,
                              Set<String> used) {
        Set<String> siblingNames = new HashSet<>();
        for (Group g : parent.children) {
            List<String> members = g.allDocs();
            Map<String, Integer> df = new HashMap<>();
            Map<String, Map<String, Integer>> spellings = new HashMap<>();
            for (String d : members) {
                for (var e : terms.getOrDefault(d, Map.of()).entrySet()) {
                    df.merge(e.getKey(), 1, Integer::sum);
                    spellings.computeIfAbsent(e.getKey(), k -> new HashMap<>()).merge(e.getValue(), 1, Integer::sum);
                }
            }
            List<Map.Entry<String, Double>> ranked = new ArrayList<>();
            for (var e : df.entrySet()) {
                double tf = (double) e.getValue() / members.size();
                if (tf < MIN_SHARED || used.contains(e.getKey()) || siblingNames.contains(e.getKey())) continue;
                double idf = Math.log(1.0 + (double) corpusSize / corpusDf.getOrDefault(e.getKey(), 1));
                ranked.add(Map.entry(e.getKey(), tf * tf * idf));
            }
            ranked.sort(Map.Entry.<String, Double>comparingByValue().reversed());

            Set<String> childUsed = new HashSet<>(used);
            if (!ranked.isEmpty()) {
                String best = ranked.get(0).getKey();
                g.name = display(spellings.get(best));
                g.terms = ranked.stream().limit(4).map(r -> display(spellings.get(r.getKey()))).toList();
                siblingNames.add(best);
                childUsed.add(best);
            } else {
                // No shared concept: name it after its best-connected document.
                String hub = members.stream().max(Comparator.comparingInt(d -> degree.getOrDefault(d, 0))).orElse(members.get(0));
                g.name = byId.get(hub).getTitle();
                g.terms = List.of();
            }
            nameChildren(g, terms, corpusDf, corpusSize, byId, degree, childUsed);
        }
    }

    private static void assignIds(Group g) {
        g.id = stableId(g.allDocs());
        for (Group c : g.children) assignIds(c);
    }

    /**
     * Rewrites concept names into readable topic names with one LLM call covering the whole tree, but only
     * when some group is new or changed membership (names are cached per group id). Returns the name source.
     */
    private String applyLlmNames(Group root, Map<String, KnowledgeNode> byId) {
        List<Group> all = new ArrayList<>();
        Deque<Group> stack = new ArrayDeque<>(root.children);
        while (!stack.isEmpty()) {
            Group g = stack.pop();
            all.add(g);
            stack.addAll(g.children);
        }
        if (all.isEmpty()) return "concepts";
        nameCache.keySet().retainAll(all.stream().map(g -> g.id).toList());

        if (all.stream().anyMatch(g -> !nameCache.containsKey(g.id))) {
            StringBuilder prompt = new StringBuilder(Prompts.TOPIC_NAMER);
            for (Group g : all) {
                prompt.append("\n[").append(g.id).append("] level ").append(g.level)
                        .append(", parent: ").append(g.parent != null && g.parent.id != null ? g.parent.id : "none")
                        .append(", ").append(g.allDocs().size()).append(" documents")
                        .append(", hint terms: ").append(String.join(", ", g.terms)).append("\n");
                g.allDocs().stream().limit(8).forEach(d ->
                        prompt.append("  - ").append(truncate(byId.get(d).getTitle(), 90)).append("\n"));
            }
            try {
                Map<String, Object> names = objectMapper.readValue(JsonText.stripFences(chatModel.chat(prompt.toString())),
                        new TypeReference<Map<String, Object>>() {});
                for (Group g : all) {
                    Object n = names.get(g.id);
                    if (n instanceof String s && !s.isBlank()) nameCache.put(g.id, truncate(s.strip(), 60));
                }
                log.info("[TopicHierarchy] LLM named {} groups", names.size());
            } catch (Exception e) {
                log.warn("[TopicHierarchy] LLM naming failed, keeping concept names: {}", e.getMessage());
            }
        }
        boolean any = false;
        for (Group g : all) {
            String n = nameCache.get(g.id);
            if (n != null) {
                g.name = n;
                any = true;
            }
        }
        return any ? "llm" : "concepts";
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /** Most common original spelling; capitalised if it was all lower case. */
    private static String display(Map<String, Integer> spellings) {
        String s = spellings.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("");
        return s.equals(s.toLowerCase()) && !s.isEmpty() ? Character.toUpperCase(s.charAt(0)) + s.substring(1) : s;
    }

    // ---------------------------------------------------------------- output

    private static void flatten(Group g, String parentId, List<Map<String, Object>> out, Map<String, String> docParent) {
        List<String> all = g.allDocs();
        for (String d : g.docs) docParent.put(d, g.id);
        List<String> childIds = new ArrayList<>();
        // Children first so their ids exist; parent entry is added before them for top-down reading.
        int at = out.size();
        for (Group c : g.children) {
            flatten(c, g.id, out, docParent);
            childIds.add(c.id);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", g.id);
        m.put("name", g.name);
        m.put("level", g.level);
        m.put("parentId", parentId);
        m.put("childClusterIds", childIds);
        m.put("docIds", g.docs);
        m.put("size", all.size());
        m.put("terms", g.terms);
        out.add(at, m);
    }

    /** Same members -> same id, so the UI's expand state survives recomputes. */
    private static String stableId(List<String> docs) {
        try {
            List<String> sorted = new ArrayList<>(docs);
            Collections.sort(sorted);
            byte[] h = MessageDigest.getInstance("SHA-1").digest(String.join(",", sorted).getBytes(StandardCharsets.UTF_8));
            return "topic-" + HexFormat.of().formatHex(h, 0, 6);
        } catch (Exception e) {
            return "topic-" + Integer.toHexString(docs.hashCode());
        }
    }

    private Map<String, Object> parseMetadata(String json) {
        try {
            return json == null || json.isBlank() ? Map.of() : objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static Map<String, Object> empty() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("clusters", List.of());
        out.put("rootClusterIds", List.of());
        out.put("rootDocIds", List.of());
        out.put("docParent", Map.of());
        out.put("algorithm", "none");
        out.put("computedAt", Instant.now().toString());
        return out;
    }
}
