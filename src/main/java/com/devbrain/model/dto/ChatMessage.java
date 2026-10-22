package com.devbrain.model.dto;

import lombok.*;

/**
 * Chat message for the knowledge Q&A endpoint.
 * Uses RAG: queries vector store, augments prompt with relevant knowledge.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatMessage {
    private String message;
    private String sessionId; // optional, for conversation continuity
}
