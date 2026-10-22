package com.devbrain.event;

import lombok.*;

/**
 * Kafka event for async content ingestion.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IngestionEvent {
    private String type;    // parser type
    private String content; // content to ingest
    private String title;   // optional title
}
