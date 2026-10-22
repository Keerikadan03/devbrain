package com.devbrain.agent.parsers;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Extracts text from a PDF given as an http(s) URL or as base64 bytes.
 * Scanned PDFs without a text layer yield no text and are reported as an error.
 */
@Slf4j
@Component
public class PdfParser implements Parser {

    private static final int MAX_PDF_BYTES = 50 * 1024 * 1024;

    private final ExtractionPipeline pipeline;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public PdfParser(ExtractionPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @Override
    public String getName() {
        return "pdf";
    }

    @Override
    public Map<String, Object> run(Map<String, Object> input) {
        String content = (String) input.get("content");
        if (content == null || content.isBlank()) {
            return Map.of("error", "No PDF provided.");
        }
        content = content.strip();
        if (content.startsWith("data:")) { // data:application/pdf;base64,....
            content = content.substring(content.indexOf(',') + 1);
        }

        try {
            boolean isUrl = content.startsWith("http://") || content.startsWith("https://");
            byte[] bytes = isUrl ? download(content) : Base64.getMimeDecoder().decode(content);

            String text;
            int pages;
            try (PDDocument doc = Loader.loadPDF(bytes)) {
                pages = doc.getNumberOfPages();
                text = new PDFTextStripper().getText(doc);
            }
            log.info("[PdfParser] Extracted {} chars from {} pages", text.length(), pages);

            Map<String, Object> extra = new HashMap<>();
            extra.put("pages", pages);
            if (isUrl) extra.put("website_urls", java.util.List.of(content));
            return pipeline.process(getName(), text, isUrl ? content : null, (String) input.get("title"), extra);

        } catch (Exception e) {
            log.error("[PdfParser] Failed", e);
            return Map.of("error", "Failed to read PDF", "details", String.valueOf(e.getMessage()));
        }
    }

    private byte[] download(String url) throws Exception {
        HttpResponse<byte[]> res = http.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + res.statusCode() + " fetching PDF");
        }
        if (res.body().length > MAX_PDF_BYTES) {
            throw new IllegalStateException("PDF exceeds " + MAX_PDF_BYTES / 1024 / 1024 + " MB limit");
        }
        return res.body();
    }
}
