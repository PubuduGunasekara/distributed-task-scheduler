package com.taskscheduler.infrastructure.kafka;

import com.taskscheduler.config.KafkaTopicConfig;
import com.taskscheduler.domain.event.TaskEvent;
import com.taskscheduler.domain.event.TaskEventType;
import com.taskscheduler.domain.model.Task;
import com.taskscheduler.domain.port.TaskEventPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Kafka implementation of TaskEventPort.
 *
 * This class is the only place in the codebase that knows about Kafka.
 * The domain knows nothing about KafkaTemplate, topics, or partitions.
 *
 * Key decisions:
 *
 * 1. Partition key = taskId.toString()
 *    All events for a given task go to the same partition.
 *    Kafka guarantees ORDER within a partition.
 *    This means: TASK_CREATED always arrives before TASK_STARTED
 *    for the same task, even if published milliseconds apart.
 *    Without this key, events for the same task could land on
 *    different partitions and be processed out of order.
 *
 * 2. Async publish via whenComplete()
 *    kafkaTemplate.send() is non-blocking — returns a CompletableFuture.
 *    The main thread continues immediately. The callback fires when
 *    the broker acknowledges receipt (or fails).
 *    This trades some durability for throughput. The outbox pattern
 *    (writing events to DB atomically with the task) is the
 *    production solution — noted for a future milestone.
 *
 * 3. Log on failure, don't rethrow
 *    If Kafka is temporarily down, we log the failure.
 *    The task is already saved in PostgreSQL.
 *    M6 will add proper retry and dead-letter handling.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskEventPublisher implements TaskEventPort {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Override
    public void publish(Task task, TaskEventType eventType) {
        TaskEvent event      = TaskEvent.from(task, eventType);
        String   partitionKey = task.getId().toString();

        kafkaTemplate.send(KafkaTopicConfig.TASK_EVENTS_TOPIC, partitionKey, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error(
                                "Failed to publish event: eventType={} taskId={} error={}",
                                eventType, task.getId(), ex.getMessage()
                        );
                    } else {
                        log.info(
                                "Event published: eventType={} taskId={} partition={} offset={}",
                                eventType,
                                task.getId(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset()
                        );
                    }
                });
    }

    @Override
    public void publishDeadLetter(Task task) {
        TaskEvent event = TaskEvent.from(task, TaskEventType.TASK_FAILED);

        kafkaTemplate.send(KafkaTopicConfig.TASK_DLQ_TOPIC, task.getId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish dead letter: taskId={} error={}",
                                task.getId(), ex.getMessage());
                    } else {
                        log.warn("Dead letter published: taskId={} retryCount={} partition={} offset={}",
                                task.getId(), task.getRetryCount(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }

}