package com.devbrain.service;

import com.devbrain.model.entity.KnowledgeNode;
import com.devbrain.model.neo4j.RelationType;
import com.devbrain.util.JsonText;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static com.devbrain.util.Prompts.RELATIONSHIP_CLASSIFIER;

/**
 * Decides, with one LLM call per new document, which candidate documents are genuinely related
 * to it and how (one of the 8 RelationTypes, plus direction for directional types).
 * Candidates come from cheap recall (vectors + shared concepts); this is the precision step.
 */
@Slf4j
@Service
public class RelationshipClassifier {

    /**
     * Verdict for one candidate. For directional types, newIsSource tells whether the edge goes
     * NEW -> candidate (e.g. "new builds upon candidate") or candidate -> NEW.
     */
    public record Classification(int candidateIndex, boolean related, RelationType type,
                                 boolean newIsSource, double strength, String explanation) {}

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    public RelationshipClassifier(ChatModel chatModel, ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
    }

    /**
     * Classifies all candidates in one call. On any failure returns an empty list: no links is
     * better than falling back to raw vector similarity, which links unrelated documents.
     */
    public List<Classification> classify(KnowledgeNode newNode, List<KnowledgeNode> candidates,
                                         Function<KnowledgeNode, String> describe) {
        StringBuilder prompt = new StringBuilder(RELATIONSHIP_CLASSIFIER)
                .append("NEW DOCUMENT\n").append(describe.apply(newNode)).append("\n\nCANDIDATES\n");
        for (int i = 0; i < candidates.size(); i++) {
            prompt.append("[").append(i + 1).append("] ").append(describe.apply(candidates.get(i))).append("\n");
        }

        List<Classification> out = new ArrayList<>();
        try {
            String reply = chatModel.chat(prompt.toString());
            List<Map<String, Object>> verdicts = objectMapper.readValue(JsonText.stripFences(reply),
                    new TypeReference<List<Map<String, Object>>>() {});
            for (Map<String, Object> v : verdicts) {
                int idx = v.get("id") instanceof Number n ? n.intValue() - 1 : -1;
                if (idx < 0 || idx >= candidates.size()) continue;
                boolean related = Boolean.TRUE.equals(v.get("related"));
                double strength = v.get("strength") instanceof Number n ? n.doubleValue() : 0;
                RelationType type = RelationType.fromCode((String) v.get("type"));
                boolean newIsSource = !"candidate".equalsIgnoreCase(String.valueOf(v.get("from")));
                String explanation = String.valueOf(v.getOrDefault("explanation", ""));
                out.add(new Classification(idx, related, type, newIsSource, strength, explanation));
            }
        } catch (Exception e) {
            log.warn("[RelationshipClassifier] Classification failed for '{}': {}", newNode.getTitle(), e.getMessage());
        }
        return out;
    }
}
