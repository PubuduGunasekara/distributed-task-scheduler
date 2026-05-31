package com.taskscheduler.worker.consumer;

import com.taskscheduler.config.KafkaTopicConfig;
import com.taskscheduler.domain.event.TaskEvent;
import com.taskscheduler.worker.service.TaskWorkerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Kafka consumer — receives task events and dispatches to TaskWorkerService.
 *
 * Separation of concerns:
 *   TaskEventConsumer   → Kafka mechanics (receive, ack, log offset metadata)
 *   TaskWorkerService   → Business logic (what to do with the event)
 *
 * This means TaskWorkerService is testable with plain Mockito —
 * no Kafka infrastructure needed in unit tests.
 *
 * Ack strategy:
 *   Success   → ack() commits the offset. Message won't be redelivered.
 *   Exception → ack() still called after logging.
 *               Task is in FAILED state in DB; M6 schedules retries.
 *               Not acking would cause infinite redelivery in local dev.
 *
 * Each @KafkaListener method runs on its own consumer thread.
 * With concurrency=3, three instances of this listener run in parallel,
 * each assigned to one partition. Partition assignment is automatic.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskEventConsumer {

    private final TaskWorkerService workerService;

    @KafkaListener(
            topics            = KafkaTopicConfig.TASK_EVENTS_TOPIC,
            groupId           = "${spring.application.name}",
            containerFactory  = "kafkaListenerContainerFactory"
    )
    public void consume(
            ConsumerRecord<String, TaskEvent> record,
            Acknowledgment ack
    ) {
        TaskEvent event = record.value();

        log.info("Received: eventType={} taskId={} partition={} offset={}",
                event.eventType(), event.taskId(),
                record.partition(), record.offset());

        try {
            workerService.process(event);
        } catch (Exception ex) {
            // Unexpected error — log and ack to avoid infinite loop.
            // M6 adds structured retry and dead-letter queue.
            log.error("Unexpected error processing event: eventType={} taskId={}",
                    event.eventType(), event.taskId(), ex);
        } finally {
            // Always ack in M4. M6 replaces this with conditional ack/nack.
            ack.acknowledge();
        }
    }
}