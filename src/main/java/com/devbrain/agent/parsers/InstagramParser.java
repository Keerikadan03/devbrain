package com.devbrain.agent.parsers;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Ingests an Instagram post/reel: caption and author, plus a Whisper transcript of the spoken audio.
 * Falls back to caption only if transcription is unavailable.
 * Private or login-walled posts need devbrain.ytdlp.cookies-file.
 */
@Slf4j
@Component
public class InstagramParser implements Parser {

    /** Also used by SourceDetector so auto-detection and the parser always agree. */
    public static final Pattern IG_URL =
            Pattern.compile("^https?://(www\\.)?instagram\\.com/(p|reel|reels|tv)/\\S+$");

    private final YtDlp ytDlp;
    private final Transcriber transcriber;
    private final ExtractionPipeline pipeline;

    public InstagramParser(YtDlp ytDlp, Transcriber transcriber, ExtractionPipeline pipeline) {
        this.ytDlp = ytDlp;
        this.transcriber = transcriber;
        this.pipeline = pipeline;
    }

    @Override
    public String getName() {
        return "instagram";
    }

    @Override
    public Map<String, Object> run(Map<String, Object> input) {
        String url = input.get("content") == null ? "" : ((String) input.get("content")).strip();
        if (!IG_URL.matcher(url).matches()) {
            return Map.of("error", "Not a valid Instagram post or reel URL.");
        }
        log.info("[InstagramParser] Fetching: {}", url);

        try {
            JsonNode info = ytDlp.fetch(url).info();
            String caption = info.path("description").asString("");
            String author = info.path("uploader").asString(info.path("channel").asString(""));
            String spoken = transcriber.transcribe(url).orElse("");
            String text = "Instagram post by " + author + "\n\nCaption: " + caption
                    + (spoken.isBlank() ? "" : "\n\nSpoken audio transcript: " + spoken);

            Map<String, Object> extra = new HashMap<>();
            extra.put("website_urls", List.of(url));
            extra.put("author", author);
            extra.put("has_transcript", !spoken.isBlank());
            return pipeline.process(getName(), text, url, (String) input.get("title"), extra);

        } catch (Exception e) {
            log.error("[InstagramParser] Failed", e);
            return Map.of("error", "Failed to ingest Instagram post", "details", String.valueOf(e.getMessage()));
        }
    }
}
