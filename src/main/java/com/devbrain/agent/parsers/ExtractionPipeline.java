package com.devbrain.agent.parsers;

import com.devbrain.service.KnowledgeService;
import com.devbrain.util.JsonText;
import com.devbrain.util.TextTruncator;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

import static com.devbrain.util.Prompts.TEXT_EXTRACT;

/**
 * Shared tail of the ingestion flow for parsers that already have raw text:
 * LLM extracts title/summary/tags, then the node is saved with auto-linking.
 * The raw text (not the LLM's rewrite) is stored as the node content, so long
 * sources like transcripts and PDFs are not cut down to the LLM's output limit.
 */
@Slf4j
@Component
public class ExtractionPipeline {

    private static final int MAX_STORED_CHARS = 200_000;

    private final ChatModel chatModel;
    private final KnowledgeService knowledgeService;
    private final ObjectMapper objectMapper;

    public ExtractionPipeline(ChatModel chatModel, KnowledgeService knowledgeService, ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.knowledgeService = knowledgeService;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> process(String source, String rawText, String sourceUrl,
                                       String userTitle, Map<String, Object> extra) {
        if (rawText == null || rawText.isBlank()) {
            return Map.of("error", "No extractable text found.");
        }
        try {
            String result = chatModel.chat(TEXT_EXTRACT + TextTruncator.truncate(rawText));
            Map<String, Object> parsed = objectMapper.readValue(JsonText.stripFences(result), new TypeReference<>() {});
            parsed.put("source", source);
            parsed.put("sourceUrl", sourceUrl);
            parsed.put("text", rawText.length() > MAX_STORED_CHARS ? rawText.substring(0, MAX_STORED_CHARS) : rawText);
            parsed.put("extra", extra != null ? extra : new HashMap<>());
            if (userTitle != null && !userTitle.isBlank()) {
                parsed.put("title", userTitle);
            }
            knowledgeService.saveFromParsedResult(parsed, true);
            return parsed;
        } catch (Exception e) {
            log.error("[{}] Processing failed", source, e);
            return Map.of("error", "Processing failed", "details", String.valueOf(e.getMessage()));
        }
    }
}
