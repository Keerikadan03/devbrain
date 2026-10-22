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
 * Parses raw text input — notes, code snippets, articles pasted in.
 */
@Slf4j
@Component
public class TextParser implements Parser {

    private final ChatModel chatModel;
    private final KnowledgeService knowledgeService;
    private final ObjectMapper objectMapper;

    public TextParser(ChatModel chatModel, KnowledgeService knowledgeService, ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.knowledgeService = knowledgeService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "text";
    }

    @Override
    public Map<String, Object> run(Map<String, Object> input) {
        String text = (String) input.get("content");
        if (text == null || text.isBlank()) {
            return Map.of("error", "No text provided.");
        }

        log.info("[TextParser] Processing {} chars of text", text.length());

        String truncated = TextTruncator.truncate(text);
        String prompt = TEXT_EXTRACT + truncated;

        try {
            String result = chatModel.chat(prompt);
            Map<String, Object> parsed = objectMapper.readValue(JsonText.stripFences(result), new TypeReference<>() {});
            parsed.put("source", getName());
            parsed.put("extra", new HashMap<>());

            // If user provided a title, override
            String userTitle = (String) input.get("title");
            if (userTitle != null && !userTitle.isBlank()) {
                parsed.put("title", userTitle);
            }

            knowledgeService.saveFromParsedResult(parsed, true);
            return parsed;

        } catch (Exception e) {
            log.error("[TextParser] Processing failed", e);
            return Map.of("error", "Processing failed", "details", String.valueOf(e.getMessage()));
        }
    }
}
