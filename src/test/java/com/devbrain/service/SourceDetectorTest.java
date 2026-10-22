package com.devbrain.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SourceDetectorTest {

    @Test
    void youtubeUrls() {
        assertEquals("youtube", SourceDetector.detectLocally("https://www.youtube.com/watch?v=jNQXAC9IVRw"));
        assertEquals("youtube", SourceDetector.detectLocally("https://youtu.be/jNQXAC9IVRw"));
        assertEquals("youtube", SourceDetector.detectLocally("https://m.youtube.com/shorts/abc123"));
        assertEquals("youtube", SourceDetector.detectLocally("https://music.youtube.com/watch?v=abc"));
    }

    @Test
    void instagramPostsAndReels() {
        assertEquals("instagram", SourceDetector.detectLocally("https://www.instagram.com/reels/DexM8RuJ5RQ/"));
        assertEquals("instagram", SourceDetector.detectLocally("https://www.instagram.com/reel/DexM8RuJ5RQ/"));
        assertEquals("instagram", SourceDetector.detectLocally("https://instagram.com/p/ABC123/"));
        assertNull(SourceDetector.detectLocally("https://www.instagram.com/someprofile/")); // not a post: needs a web check
    }

    @Test
    void pdfs() {
        assertEquals("pdf", SourceDetector.detectLocally("https://example.com/files/paper.PDF"));
        assertEquals("pdf", SourceDetector.detectLocally("https://example.com/paper.pdf?download=1"));
        assertEquals("pdf", SourceDetector.detectLocally("https://arxiv.org/pdf/1706.03762"));
        assertEquals("pdf", SourceDetector.detectLocally("JVBERi0xLjQKJcfsj6IKNSAwIG9iago8PC9MZW5ndGggNiAwIFI+PgpzdHJlYW0K"));
        assertEquals("pdf", SourceDetector.detectLocally("data:application/pdf;base64,JVBERi0xLjQK"));
    }

    @Test
    void ordinaryWebAddressesNeedANetworkCheck() {
        assertNull(SourceDetector.detectLocally("https://docs.docker.com/"));
        assertNull(SourceDetector.detectLocally("https://arxiv.org/abs/1706.03762"));
    }

    @Test
    void everythingElseIsText() {
        assertEquals("text", SourceDetector.detectLocally("PHP"));
        assertEquals("text", SourceDetector.detectLocally("Remember to read https://www.youtube.com/watch?v=abc later"));
        assertEquals("text", SourceDetector.detectLocally("https://a.com/x https://b.com/y")); // several links
        assertEquals("text", SourceDetector.detectLocally("public static void main(String[] args) {}"));
    }

    @Test
    void imagesAreRecognisedButUnsupported() {
        assertEquals("image", SourceDetector.detectLocally("data:image/png;base64,iVBORw0KGgo="));
        assertEquals("image", SourceDetector.detectLocally("/9j/" + "A".repeat(300)));
    }
}
