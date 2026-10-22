package com.devbrain.controller;

import com.devbrain.model.dto.ChatMessage;
import com.devbrain.service.ChatService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST API for RAG-based chat.
 * Uses knowledge base context to answer engineering questions.
 */
@RestController
@RequestMapping("/api/chat")
@CrossOrigin(origins = "*")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * POST /api/chat
     * Sends a message to the RAG-powered chat.
     * Retrieves relevant knowledge base context and generates an answer.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> chat(@RequestBody ChatMessage message) {
        String sessionId = message.getSessionId() != null && !message.getSessionId().isBlank()
                ? message.getSessionId() : java.util.UUID.randomUUID().toString();
        ChatService.Answer answer = chatService.chat(message.getMessage(), sessionId);
        return ResponseEntity.ok(Map.of(
                "response", answer.response(),
                "sessionId", sessionId,
                "sources", answer.sources()
        ));
    }
}
