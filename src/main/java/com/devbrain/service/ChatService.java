package com.devbrain.service;

import com.devbrain.model.entity.KnowledgeNode;
import com.devbrain.util.TextTruncator;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Knowledge-base-grounded chat (RAG + graph).
 *
 * Retrieval: chunk-level vector search finds the most relevant passages, grouped by document;
 * the graph then adds documents linked to those (similar, or sharing concepts).
 * The model may only answer from that context. When the context is not enough it must say what is
 * missing and ask before using outside knowledge, so a later "yes" in the same session unlocks it.
 */
@Slf4j
@Service
public class ChatService {

    private static final int SEED_DOCS = 4;
    private static final int CHUNKS_PER_DOC = 6;
    private static final int GRAPH_DOCS = 4;
    private static final int HISTORY_MESSAGES = 12;
    private static final int MAX_SESSIONS = 200;
    private static final double MIN_SCORE = 0.25; // MiniLM scores are low: unrelated ~0.1-0.2, related 0.4+

    /** What the answer was grounded in; returned to the UI as citations. */
    public record Source(String id, String title, String source, String url, String relation) {}

    public record Answer(String response, List<Source> sources) {}

    private static final String RULES = """
            You are DevBrain, a research assistant that answers ONLY from the user's own knowledge base.

            RULES
            1. Use only the KNOWLEDGE BASE CONTEXT below. Do not use outside or general knowledge, and do not guess.
            2. Combine information across several documents when it helps: point out where sources agree,
               add to each other, or conflict. The "Related via graph" documents are linked to the main documents
               with a typed relationship (same concept, shared idea, opposing view, builds upon, example of,
               supersedes, depends on, contrasts with) and an explanation. Use the type: point out opposing views,
               say when one source supersedes another (prefer the newer), and explain prerequisites.
            3. Cite the document title in square brackets after each claim, e.g. [PHP2SHELL: Two PHP Engine Vulnerabilities].
            4. If the context does not contain enough to answer fully, say clearly what is missing, answer only the
               part the context supports, and then ASK the user whether they want you to fill the gap with outside
               general knowledge (or suggest what to add to the knowledge base). Do not use outside knowledge
               until the user has said yes in this conversation.
            5. If the user has agreed to outside knowledge, put it in a separate section titled
               "Outside knowledge (not from your knowledge base)" so it is never mixed up with sourced facts.
            6. The context holds only the passages retrieved for THIS message, not the whole knowledge base.
               Never say that a document is, or is not, in the knowledge base because it is missing from the context,
               never list what the knowledge base "contains", and never correct or contradict your earlier replies on that basis.
            7. If the context is empty or unrelated, do not answer the question; say the knowledge base has nothing on it
               and ask what they want to do. Be concise and use markdown.
            """;

    private final ChatModel chatModel;
    private final KnowledgeService knowledgeService;

    private final Map<String, Deque<ChatMessage>> sessions = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Deque<ChatMessage>> e) {
                    return size() > MAX_SESSIONS;
                }
            });

    public ChatService(ChatModel chatModel, KnowledgeService knowledgeService) {
        this.chatModel = chatModel;
        this.knowledgeService = knowledgeService;
    }

    public Answer chat(String message, String sessionId) {
        String sid = (sessionId == null || sessionId.isBlank()) ? UUID.randomUUID().toString() : sessionId;
        Deque<ChatMessage> history = sessions.computeIfAbsent(sid, k -> new ArrayDeque<>());

        // A short follow-up ("yes", "and the second one?") has no meaning on its own: retrieve on the last question too.
        String query = message;
        synchronized (history) {
            if (message.length() < 40) {
                String prev = lastUserMessage(history);
                if (prev != null) query = prev + " " + message;
            }
        }

        List<Source> sources = new ArrayList<>();
        String context = buildContext(query, sources);
        log.info("[ChatService] '{}' -> {} sources", message, sources.size());

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(RULES + "\nKNOWLEDGE BASE CONTEXT\n" + context));
        synchronized (history) {
            messages.addAll(history);
        }
        messages.add(UserMessage.from(message));

        String reply = chatModel.chat(messages).aiMessage().text();

        synchronized (history) {
            history.addLast(UserMessage.from(message));
            history.addLast(AiMessage.from(reply));
            while (history.size() > HISTORY_MESSAGES) history.removeFirst();
        }
        // Show only what the answer actually cited, not everything that was retrieved.
        String lower = reply.toLowerCase();
        List<Source> cited = sources.stream().filter(src -> lower.contains(src.title().toLowerCase())).toList();
        return new Answer(reply, cited);
    }

    private String buildContext(String query, List<Source> sources) {
        List<EmbeddingMatch<TextSegment>> matches = knowledgeService.searchChunks(query, 30, MIN_SCORE);

        // Group passages by document, in order of best score.
        Map<String, List<String>> passages = new LinkedHashMap<>();
        for (EmbeddingMatch<TextSegment> m : matches) {
            String nodeId = m.embedded().metadata().getString("nodeId");
            if (nodeId == null) continue;
            if (!passages.containsKey(nodeId) && passages.size() >= SEED_DOCS) continue;
            List<String> list = passages.computeIfAbsent(nodeId, k -> new ArrayList<>());
            if (list.size() < CHUNKS_PER_DOC) list.add(m.embedded().text());
        }
        if (passages.isEmpty()) return "(Nothing relevant was found in the knowledge base.)";

        StringBuilder ctx = new StringBuilder("## Main documents\n");
        for (var e : passages.entrySet()) {
            Optional<KnowledgeNode> node = knowledgeService.findNode(e.getKey());
            if (node.isEmpty()) continue;
            KnowledgeNode n = node.get();
            sources.add(new Source(e.getKey(), n.getTitle(), n.getSource(), n.getSourceUrl(), "retrieved"));
            ctx.append("\n### ").append(n.getTitle()).append(" (").append(n.getSource())
                    .append(n.getSourceUrl() != null ? ", " + n.getSourceUrl() : "").append(")\n")
                    .append("Summary: ").append(nullToEmpty(n.getSummary())).append("\nRelevant passages:\n");
            for (String p : e.getValue()) ctx.append("- ").append(TextTruncator.truncate(p, 1200).replace("\n", " ")).append("\n");
        }

        // Graph expansion: documents linked to the main ones, with the reason for the link.
        Set<String> seen = new HashSet<>(passages.keySet());
        StringBuilder related = new StringBuilder();
        int added = 0;
        for (Map<String, Object> r : knowledgeService.neighbors(passages.keySet())) {
            String id = String.valueOf(r.get("id"));
            if (added >= GRAPH_DOCS || !seen.add(id)) continue;
            Optional<KnowledgeNode> node = knowledgeService.findNode(id);
            if (node.isEmpty()) continue;
            KnowledgeNode n = node.get();
            String why = r.get("kind") + (r.get("detail") != null ? " (" + r.get("detail") + ")" : "");
            sources.add(new Source(id, n.getTitle(), n.getSource(), n.getSourceUrl(), "graph: " + why));
            related.append("\n### ").append(n.getTitle()).append(" (").append(n.getSource()).append(")\n")
                    .append("Linked because: ").append(why).append("\nSummary: ").append(nullToEmpty(n.getSummary())).append("\n");
            added++;
        }
        if (added > 0) ctx.append("\n## Related via graph\n").append(related);
        return ctx.toString();
    }

    private static String lastUserMessage(Deque<ChatMessage> history) {
        String last = null;
        for (ChatMessage m : history) {
            if (m instanceof UserMessage u && u.singleText() != null) last = u.singleText();
        }
        return last;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
