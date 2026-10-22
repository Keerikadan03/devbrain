package com.devbrain.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnTransformer;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity stored in PostgreSQL.
 * Holds the content, embeddings metadata, and source info.
 * The actual vector embedding is stored via pgvector (managed by Langchain4j EmbeddingStore).
 */
@Entity
@Table(name = "knowledge_nodes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KnowledgeNode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(nullable = false)
    private String source; // "url", "text", "image", "youtube"

    @Column(columnDefinition = "TEXT")
    private String sourceUrl;

    @Column(columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb") // JDBC binds the String as varchar; Postgres needs the cast
    private String metadata; // extra JSON data (tags, website_urls, youtube_urls, etc.)

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
