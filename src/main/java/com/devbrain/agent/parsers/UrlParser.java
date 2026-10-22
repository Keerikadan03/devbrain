package com.devbrain.agent.parsers;

import com.devbrain.service.KnowledgeService;
import com.devbrain.util.JsonText;
import com.devbrain.util.TextTruncator;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static com.devbrain.util.Prompts.TEXT_EXTRACT;

/**
 * Fetches a webpage via Jsoup, sends content to LLM for extraction,
 * then saves via KnowledgeService with auto-linking.
 * Ported from Bits2Brain's UrlParser.
 */
@Slf4j
@Component
public class UrlParser implements Parser {

    private final ChatModel chatModel;
    private final KnowledgeService knowledgeService;
    private final ObjectMapper objectMapper;

    public UrlParser(ChatModel chatModel, KnowledgeService knowledgeService, ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.knowledgeService = knowledgeService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "url";
    }

    @Override
    public Map<String, Object> run(Map<String, Object> input) {
        String url = (String) input.get("content");
        if (url == null || url.isBlank()) {
            return Map.of("error", "No URL provided.");
        }

        log.info("[UrlParser] Fetching: {}", url);

        // 1. Fetch webpage
        String pageText;
        Instant t1 = Instant.now();
        try {
            Document doc = Jsoup.connect(url)
                    .userAgent("DevBrain/1.0")
                    .timeout(10_000)
                    .get();
            pageText = doc.body().text();
        } catch (org.jsoup.HttpStatusException e) {
            log.warn("[UrlParser] {} answered HTTP {}", url, e.getStatusCode());
            int code = e.getStatusCode();
            String why = (code == 401 || code == 403 || code == 429 || code == 503)
                    ? "The site blocked automated access (HTTP " + code + "), often a bot check. "
                      + "Open the page in your browser, copy the article text, and add it with the Text option."
                    : "The site answered HTTP " + code + ".";
            return Map.of("error", "Failed to load webpage", "details", why);
        } catch (IOException e) {
            log.error("[UrlParser] Fetch failed: {}", e.getMessage());
            return Map.of("error", "Failed to load webpage", "details", String.valueOf(e.getMessage()));
        }
        Instant t2 = Instant.now();
        log.info("[UrlParser] Fetched in {} ms", Duration.between(t1, t2).toMillis());

        // 2. Truncate and send to LLM
        String truncated = TextTruncator.truncate(pageText);
        String prompt = TEXT_EXTRACT + truncated;

        Instant t3 = Instant.now();
        try {
            String result = chatModel.chat(prompt);
            Instant t4 = Instant.now();
            log.info("[UrlParser] LLM responded in {} ms", Duration.between(t3, t4).toMillis());

            Map<String, Object> parsed = objectMapper.readValue(JsonText.stripFences(result), new TypeReference<>() {});
            parsed.put("source", getName());
            parsed.put("sourceUrl", url);

            Map<String, Object> extra = new HashMap<>();
            extra.put("website_urls", List.of(url));
            parsed.put("extra", extra);
            parsed.put("timing", Map.of(
                    "fetchTimeMs", Duration.between(t1, t2).toMillis(),
                    "llmTimeMs", Duration.between(t3, t4).toMillis()
            ));

            // 3. Save to knowledge base with auto-linking
            knowledgeService.saveFromParsedResult(parsed, true);
            return parsed;

        } catch (Exception e) {
            log.error("[UrlParser] Processing failed", e);
            return Map.of("error", "Processing failed", "details", String.valueOf(e.getMessage()));
        }
    }
}
