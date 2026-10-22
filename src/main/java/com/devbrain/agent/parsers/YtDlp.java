package com.devbrain.agent.parsers;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Thin wrapper over the yt-dlp CLI for fetching video metadata and subtitles
 * without downloading media. Requires yt-dlp on PATH (or devbrain.ytdlp.binary).
 */
@Slf4j
@Component
public class YtDlp {

    /** Metadata plus cleaned subtitle text (empty if the video has no captions). */
    public record Result(JsonNode info, String transcript) {}

    private static final Pattern VTT_TAG = Pattern.compile("<[^>]+>");

    private final ObjectMapper objectMapper;

    @Value("${devbrain.ytdlp.binary:yt-dlp}")
    private String binary;

    @Value("${devbrain.ytdlp.cookies-file:}")
    private String cookiesFile;

    public YtDlp(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Result fetch(String url) throws Exception {
        Path dir = Files.createTempDirectory("devbrain-ytdlp");
        try {
            List<String> cmd = new ArrayList<>(List.of(binary,
                    "--skip-download", "--write-info-json", "--write-subs", "--write-auto-subs",
                    "--sub-langs", "en", "--sub-format", "vtt", "--no-playlist",
                    "-o", dir.resolve("v.%(ext)s").toString()));
            if (cookiesFile != null && !cookiesFile.isBlank()) {
                cmd.addAll(List.of("--cookies", cookiesFile));
            }
            cmd.add("--"); // stop option parsing so a crafted URL can't inject flags
            cmd.add(url);

            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            if (!p.waitFor(120, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException("yt-dlp timed out");
            }
            // A failed extra subtitle track (e.g. 429) exits non-zero but may still leave usable output.
            if (p.exitValue() != 0 && !Files.exists(dir.resolve("v.info.json"))) {
                String hint = out.contains("--cookies") || out.contains("login")
                        ? " (login required: set devbrain.ytdlp.cookies-file to an exported cookies.txt)" : "";
                throw new IllegalStateException("yt-dlp failed: " + tail(out) + hint);
            }

            JsonNode info = objectMapper.readTree(Files.readString(dir.resolve("v.info.json")));
            String transcript = "";
            try (Stream<Path> files = Files.list(dir)) {
                Path vtt = files.filter(f -> f.getFileName().toString().endsWith(".vtt")).sorted().findFirst().orElse(null);
                if (vtt != null) transcript = cleanVtt(Files.readString(vtt));
            }
            return new Result(info, transcript);
        } catch (java.io.IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("error=2")) {
                throw new IllegalStateException("yt-dlp is not installed (brew install yt-dlp)", e);
            }
            throw e;
        } finally {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
            }
        }
    }

    /** Downloads the audio track as 16 kHz mono WAV (what whisper.cpp expects) into dir; returns its path. */
    public Path downloadAudio(String url, Path dir) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(binary, "-x", "--audio-format", "wav",
                "--postprocessor-args", "ffmpeg:-ar 16000 -ac 1", "--no-playlist",
                "-o", dir.resolve("a.%(ext)s").toString()));
        if (cookiesFile != null && !cookiesFile.isBlank()) {
            cmd.addAll(List.of("--cookies", cookiesFile));
        }
        cmd.add("--");
        cmd.add(url);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(300, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("audio download timed out");
        }
        Path wav = dir.resolve("a.wav");
        if (p.exitValue() != 0 || !Files.exists(wav)) {
            throw new IllegalStateException("audio download failed: " + tail(out));
        }
        return wav;
    }

    /** Strips WEBVTT headers, cue timings and inline tags; collapses the rolling-caption repeats. */
    static String cleanVtt(String vtt) {
        StringBuilder sb = new StringBuilder();
        String prev = "";
        for (String line : vtt.split("\\R")) {
            String l = line.strip();
            if (l.isEmpty() || l.startsWith("WEBVTT") || l.startsWith("Kind:") || l.startsWith("Language:")
                    || l.startsWith("NOTE") || l.contains("-->")) continue;
            l = VTT_TAG.matcher(l).replaceAll("").strip();
            if (l.isEmpty() || l.equals(prev)) continue;
            sb.append(l).append(' ');
            prev = l;
        }
        return sb.toString().strip();
    }

    private static String tail(String s) {
        return s.length() > 500 ? s.substring(s.length() - 500) : s;
    }
}
