package com.taskscheduler.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskscheduler.domain.event.TaskEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.Map;

/**
 * Explicit Kafka consumer configuration.
 *
 * Why manual offset commit (AckMode.MANUAL_IMMEDIATE)?
 *   Auto-commit marks a message "done" the moment it's received.
 *   If the worker crashes after receiving but before finishing,
 *   the task is lost — Kafka won't redeliver it.
 *   With MANUAL_IMMEDIATE, we call ack.acknowledge() only after
 *   the task state is committed to PostgreSQL.
 *   Crash between receive and ack = message redelivered = task retried.
 *   This is at-least-once delivery. The task's state machine (PENDING
 *   guard in startTask) prevents double-execution.
 *
 * Why setConcurrency(3)?
 *   Our topic has 3 partitions. Each partition is assigned to one
 *   consumer thread. 3 threads = all partitions covered in parallel.
 *   More threads than partitions = idle threads (wasteful).
 *   Fewer threads = some partitions processed sequentially (slower).
 */
@EnableKafka
@Configuration
public class KafkaConsumerConfig {

    @Bean
    public ConsumerFactory<String, TaskEvent> consumerFactory(
            KafkaProperties kafkaProperties,
            ObjectMapper objectMapper
    ) {
        Map<String, Object> props = kafkaProperties.buildConsumerProperties(null);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        // Mirror the producer: no type headers, deserialize directly to TaskEvent.
        // USE_TYPE_INFO_HEADERS=false because we set ADD_TYPE_INFO_HEADERS=false
        // on the producer side. The deserializer must know the target type explicitly.
        JsonDeserializer<TaskEvent> deserializer =
                new JsonDeserializer<>(TaskEvent.class, objectMapper);
        deserializer.setUseTypeHeaders(false);

        return new DefaultKafkaConsumerFactory<>(
                props,
                new StringDeserializer(),
                deserializer
        );
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TaskEvent>
    kafkaListenerContainerFactory(ConsumerFactory<String, TaskEvent> consumerFactory) {

        ConcurrentKafkaListenerContainerFactory<String, TaskEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);

        // 1 thread per partition = full parallelism for our 3-partition topic.
        factory.setConcurrency(3);

        // MANUAL_IMMEDIATE: ack is committed to Kafka broker immediately
        // when ack.acknowledge() is called (not batched).
        factory.getContainerProperties()
                .setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

        return factory;
    }
}