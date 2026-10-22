package com.devbrain.service;

import com.devbrain.agent.parsers.InstagramParser;
import com.devbrain.agent.parsers.YoutubeParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Works out which parser should handle a piece of content, so callers can just submit "anything".
 *
 * Returns a parser name ("youtube", "instagram", "pdf", "url", "text") or "image" for content
 * that is recognised but has no parser yet. Anything unrecognised is treated as plain text.
 */
@Slf4j
@Component
public class SourceDetector {

    private static final Pattern SINGLE_URL = Pattern.compile("^https?://\\S+$");
    private static final Pattern PDF_PATH = Pattern.compile("(?i)^[^?#]*\\.pdf([?#].*)?$");
    private static final Pattern BASE64 = Pattern.compile("^[A-Za-z0-9+/=\\s]+$");

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(4))
            .build();

    public String detect(String content) {
        String c = content == null ? "" : content.strip();
        if (c.isEmpty()) return "text";

        String local = detectLocally(c);
        if (local != null) return local;

        // A plain web address: could still be a PDF served without a .pdf extension.
        return isPdfByHeader(c) ? "pdf" : "url";
    }

    /**
     * Everything that can be decided without touching the network; null means "a web address, check further".
     */
    static String detectLocally(String c) {
        if (c.startsWith("data:application/pdf") || c.startsWith("JVBERi0")) return "pdf"; // base64 of "%PDF-"
        if (c.startsWith("data:image/") || (c.length() > 200 && BASE64.matcher(c).matches()
                && (c.startsWith("/9j/") || c.startsWith("iVBORw0KGgo")))) return "image"; // base64 JPEG / PNG

        if (!SINGLE_URL.matcher(c).matches()) return "text"; // prose, notes, code, several links...

        if (YoutubeParser.YT_URL.matcher(c).matches()) return "youtube";
        if (InstagramParser.IG_URL.matcher(c).matches()) return "instagram";
        if (PDF_PATH.matcher(c).matches()) return "pdf";
        if (c.matches("(?i)^https?://(www\\.)?arxiv\\.org/pdf/\\S+$")) return "pdf";
        return null;
    }

    private boolean isPdfByHeader(String url) {
        try {
            HttpResponse<Void> res = http.send(
                    HttpRequest.newBuilder(URI.create(url)).method("HEAD", HttpRequest.BodyPublishers.noBody())
                            .timeout(Duration.ofSeconds(5)).build(),
                    HttpResponse.BodyHandlers.discarding());
            return res.headers().firstValue("Content-Type").orElse("").toLowerCase().contains("application/pdf");
        } catch (Exception e) {
            log.debug("[SourceDetector] HEAD check failed for {}: {}", url, e.getMessage());
            return false; // fall back to treating it as a web page
        }
    }
}
