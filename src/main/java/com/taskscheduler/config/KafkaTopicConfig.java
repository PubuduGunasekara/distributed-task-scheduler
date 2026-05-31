package com.taskscheduler.config;

/**
 * Kafka topic name constants.
 * Not a @Configuration class — just a constants holder.
 * Topic creation is handled in KafkaProducerConfig.
 */
public final class KafkaTopicConfig {
    public static final String TASK_EVENTS_TOPIC = "task-events";
    public static final String TASK_DLQ_TOPIC    = "task-dlq";
    private KafkaTopicConfig() {}
}