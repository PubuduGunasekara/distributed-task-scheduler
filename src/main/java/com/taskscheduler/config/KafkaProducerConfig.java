package com.taskscheduler.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.kafka.config.TopicBuilder;

import java.util.Map;

/**
 * Explicit Kafka producer configuration.
 *
 * Why not rely on application.yml for serializer classes?
 * Spring Boot's property binding for Class-type Kafka properties
 * can be unreliable across versions — the auto-configured
 * KafkaTemplate may use StringSerializer regardless of what
 * value-serializer is set to in YAML.
 *
 * Defining ProducerFactory and KafkaTemplate as beans here
 * suppresses Spring Boot's auto-configured versions via
 * @ConditionalOnMissingBean — our beans win, guaranteed.
 *
 * JsonSerializer.ADD_TYPE_INFO_HEADERS = false:
 * By default, Spring's JsonSerializer adds a "__TypeId__" header
 * to every message. This couples producers and consumers to the
 * same class structure. Disabling it keeps messages clean JSON
 * that any consumer (including non-Java ones) can read.
 */
@Configuration
public class KafkaProducerConfig {

    @Bean
    public ProducerFactory<String, Object> producerFactory(
            KafkaProperties kafkaProperties,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        Map<String, Object> props = kafkaProperties.buildProducerProperties(null);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);

        // Reuse Spring's configured ObjectMapper so Kafka serializes
        // dates as ISO strings ("2026-06-01T10:00:00Z") not Unix timestamps
        JsonSerializer<Object> valueSerializer = new JsonSerializer<>(objectMapper);

        DefaultKafkaProducerFactory<String, Object> factory =
                new DefaultKafkaProducerFactory<>(props);
        factory.setValueSerializer(valueSerializer);
        return factory;
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate(
            ProducerFactory<String, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean
    public NewTopic taskEventsTopic() {
        return TopicBuilder.name(KafkaTopicConfig.TASK_EVENTS_TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic taskDlqTopic() {
        return TopicBuilder.name(KafkaTopicConfig.TASK_DLQ_TOPIC)
                .partitions(1)        // DLQ doesn't need parallelism — manual review
                .replicas(1)
                .build();
    }
}