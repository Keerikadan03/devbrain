package com.devbrain.event;

import com.devbrain.agent.parsers.ParserManager;
import com.devbrain.service.SourceDetector;
import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Consumes ingestion events from Kafka and routes to the appropriate parser.
 * Sends real-time updates via WebSocket.
 */
@Slf4j
@Component
public class IngestionConsumer {

    private final ParserManager parserManager;
    private final ObjectMapper objectMapper;
    private final SimpMessagingTemplate messagingTemplate;
    private final SourceDetector sourceDetector;

    public IngestionConsumer(ParserManager parserManager, ObjectMapper objectMapper,
                             SimpMessagingTemplate messagingTemplate, SourceDetector sourceDetector) {
        this.parserManager = parserManager;
        this.objectMapper = objectMapper;
        this.messagingTemplate = messagingTemplate;
        this.sourceDetector = sourceDetector;
    }

    @KafkaListener(topics = "${devbrain.kafka.topic.ingestion}", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(String payload) {
        try {
            IngestionEvent event = objectMapper.readValue(payload, IngestionEvent.class);
            if (event.getType() == null || event.getType().isBlank() || "auto".equalsIgnoreCase(event.getType())) {
                event.setType(sourceDetector.detect(event.getContent())); // events put on the topic directly
            }
            log.info("[IngestionConsumer] Processing {} event", event.getType());

            // Send "processing" status via WebSocket
            messagingTemplate.convertAndSend("/topic/ingestion",
                    (Object) Map.of("status", "processing", "type", event.getType()));

            // Route to parser
            Map<String, Object> input = new HashMap<>();
            input.put("content", event.getContent());
            input.put("title", event.getTitle());

            Map<String, Object> result = parserManager.getParser(event.getType()).run(input);

            // Send completion status
            String status = result.containsKey("error") ? "error" : "completed";
            messagingTemplate.convertAndSend("/topic/ingestion",
                    (Object) Map.of("status", status, "type", event.getType(), "result", result));

            log.info("[IngestionConsumer] {} event processed: {}", event.getType(), status);

        } catch (Exception | LinkageError e) {
            // LinkageError (e.g. NoClassDefFoundError from a missing jar) is an Error, not an
            // Exception; left uncaught it kills the listener thread and stops all ingestion.
            log.error("[IngestionConsumer] Failed to process event", e);
            messagingTemplate.convertAndSend("/topic/ingestion",
                    (Object) Map.of("status", "error", "details", String.valueOf(e.getMessage())));
        }
    }
}
