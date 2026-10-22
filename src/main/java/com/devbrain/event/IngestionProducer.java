package com.devbrain.event;

import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes ingestion events to Kafka for async processing.
 */
@Slf4j
@Component
public class IngestionProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${devbrain.kafka.topic.ingestion}")
    private String topic;

    public IngestionProducer(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public void publish(IngestionEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(topic, event.getType(), payload);
            log.info("[IngestionProducer] Published {} event to {}", event.getType(), topic);
        } catch (Exception e) {
            log.error("[IngestionProducer] Failed to publish event", e);
        }
    }
}
