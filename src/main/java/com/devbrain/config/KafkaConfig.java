package com.devbrain.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    @Value("${devbrain.kafka.topic.ingestion}")
    private String ingestionTopic;

    @Value("${devbrain.kafka.topic.research}")
    private String researchTopic;

    @Bean
    public NewTopic ingestionTopic() {
        return TopicBuilder.name(ingestionTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic researchTopic() {
        return TopicBuilder.name(researchTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
