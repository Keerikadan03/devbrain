package com.devbrain.agent.parsers;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Speech-to-text for video URLs: yt-dlp pulls the audio, whisper.cpp transcribes it locally.
 * Needs ffmpeg, whisper-cli and a ggml model (brew install ffmpeg whisper-cpp).
 * Returns empty on any failure so callers can fall back to caption/description text.
 */
@Slf4j
@Component
public class Transcriber {

    private final YtDlp ytDlp;

    @Value("${devbrain.whisper.enabled:true}")
    private boolean enabled;

    @Value("${devbrain.whisper.binary:whisper-cli}")
    private String binary;

    @Value("${devbrain.whisper.model:${user.home}/.devbrain/models/ggml-base.en.bin}")
    private String model;

    public Transcriber(YtDlp ytDlp) {
        this.ytDlp = ytDlp;
    }

    public Optional<String> transcribe(String url) {
        if (!enabled) return Optional.empty();
        if (!Files.exists(Path.of(model))) {
            log.warn("[Transcriber] Whisper model not found at {}; skipping audio transcription", model);
            return Optional.empty();
        }
        Path dir = null;
        try {
            dir = Files.createTempDirectory("devbrain-audio");
            Path wav = ytDlp.downloadAudio(url, dir);

            Process p = new ProcessBuilder(binary, "-m", model, "-f", wav.toString(), "-nt", "-np")
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            String text = new String(p.getInputStream().readAllBytes()).strip();
            if (!p.waitFor(600, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException("whisper timed out");
            }
            if (p.exitValue() != 0 || text.isBlank()) return Optional.empty();
            log.info("[Transcriber] Transcribed {} chars", text.length());
            return Optional.of(text.replaceAll("\\s+", " "));
        } catch (Exception e) {
            log.warn("[Transcriber] Transcription failed, falling back to text metadata: {}", e.getMessage());
            return Optional.empty();
        } finally {
            if (dir != null) {
                try (Stream<Path> walk = Files.walk(dir)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
                } catch (Exception ignored) {
                }
            }
        }
    }
}
