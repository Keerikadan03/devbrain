package com.devbrain.agent.parsers;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Ingests a YouTube video: metadata + captions (manual or auto-generated).
 * Falls back to title + description when the video has no English captions.
 */
@Slf4j
@Component
public class YoutubeParser implements Parser {

    /** Also used by SourceDetector so auto-detection and the parser always agree. */
    public static final Pattern YT_URL =
            Pattern.compile("^https?://((www|m|music)\\.)?(youtube\\.com|youtu\\.be)/\\S+$");

    private final YtDlp ytDlp;
    private final Transcriber transcriber;
    private final ExtractionPipeline pipeline;

    public YoutubeParser(YtDlp ytDlp, Transcriber transcriber, ExtractionPipeline pipeline) {
        this.ytDlp = ytDlp;
        this.transcriber = transcriber;
        this.pipeline = pipeline;
    }

    @Override
    public String getName() {
        return "youtube";
    }

    @Override
    public Map<String, Object> run(Map<String, Object> input) {
        String url = input.get("content") == null ? "" : ((String) input.get("content")).strip();
        if (!YT_URL.matcher(url).matches()) {
            return Map.of("error", "Not a valid YouTube URL.");
        }
        log.info("[YoutubeParser] Fetching: {}", url);

        try {
            YtDlp.Result r = ytDlp.fetch(url);
            JsonNode info = r.info();
            String title = info.path("title").asString("");
            String description = info.path("description").asString("");

            // Prefer the creator's captions; otherwise listen to the audio; last resort is the description.
            String transcript = r.transcript();
            String transcriptSource = "captions";
            if (transcript.isBlank()) {
                transcript = transcriber.transcribe(url).orElse("");
                transcriptSource = "whisper";
            }
            boolean hasTranscript = !transcript.isBlank();
            String text = "Title: " + title + "\nChannel: " + info.path("uploader").asString("") + "\n\n"
                    + (hasTranscript ? transcript : description);

            Map<String, Object> extra = new HashMap<>();
            extra.put("website_urls", List.of(url));
            extra.put("channel", info.path("uploader").asString(""));
            extra.put("duration_seconds", info.path("duration").asInt(0));
            extra.put("has_transcript", hasTranscript);
            if (hasTranscript) extra.put("transcript_source", transcriptSource);
            return pipeline.process(getName(), text, url, (String) input.get("title"), extra);

        } catch (Exception e) {
            log.error("[YoutubeParser] Failed", e);
            return Map.of("error", "Failed to ingest YouTube video", "details", String.valueOf(e.getMessage()));
        }
    }
}
