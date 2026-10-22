package com.devbrain.model.dto;

import lombok.*;

/**
 * Request to ingest content into the knowledge base.
 * The "type" field determines which parser handles it.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IngestionRequest {
    private String type;    // "url", "text", "pdf", "youtube", "instagram"
    private String content; // URL, raw text, PDF URL or base64, YouTube/Instagram URL
    private String title;   // optional user-provided title
}
