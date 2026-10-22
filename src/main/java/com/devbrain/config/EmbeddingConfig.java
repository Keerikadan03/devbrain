package com.devbrain.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers an in-process embedding model (all-MiniLM-L6-v2).
 * Runs locally via ONNX — no API key needed for embeddings.
 * This keeps the project independent of OpenAI for embeddings.
 */
@Configuration
public class EmbeddingConfig {

    @Bean
    public EmbeddingModel embeddingModel() {
        return new AllMiniLmL6V2EmbeddingModel();
    }

    /**
     * Vectors live in Postgres (pgvector) so they survive restarts. Without this bean
     * langchain4j falls back to an in-memory store and all search/RAG/linking is lost on restart.
     */
    @Bean
    public EmbeddingStore<TextSegment> embeddingStore(
            @Value("${devbrain.pgvector.host:localhost}") String host,
            @Value("${devbrain.pgvector.port:5433}") int port,
            @Value("${devbrain.pgvector.database:devbrain}") String database,
            @Value("${spring.datasource.username}") String user,
            @Value("${spring.datasource.password}") String password) {
        return PgVectorEmbeddingStore.builder()
                .host(host)
                .port(port)
                .database(database)
                .user(user)
                .password(password)
                .table("embeddings")
                .dimension(384) // all-MiniLM-L6-v2
                .createTable(true)
                .build();
    }
}
