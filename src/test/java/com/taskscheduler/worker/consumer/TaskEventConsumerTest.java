package com.taskscheduler.worker.consumer;

import com.taskscheduler.domain.event.TaskEvent;
import com.taskscheduler.domain.event.TaskEventType;
import com.taskscheduler.domain.model.TaskStatus;
import com.taskscheduler.worker.service.TaskWorkerService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TaskEventConsumer")
class TaskEventConsumerTest {

    @Mock private TaskWorkerService workerService;
    @Mock private Acknowledgment    ack;

    @InjectMocks
    private TaskEventConsumer consumer;

    @Test
    @DisplayName("should process event and acknowledge")
    void shouldProcessAndAcknowledge() {
        ConsumerRecord<String, TaskEvent> record = buildRecord(TaskEventType.TASK_CREATED);

        consumer.consume(record, ack);

        verify(workerService).process(record.value());
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("should always acknowledge even when worker throws")
    void shouldAcknowledgeEvenOnException() {
        ConsumerRecord<String, TaskEvent> record = buildRecord(TaskEventType.TASK_CREATED);
        doThrow(new RuntimeException("unexpected"))
                .when(workerService).process(any());

        consumer.consume(record, ack);

        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("should acknowledge non-TASK_CREATED events after worker skips them")
    void shouldAcknowledgeSkippedEvents() {
        ConsumerRecord<String, TaskEvent> record = buildRecord(TaskEventType.TASK_COMPLETED);

        consumer.consume(record, ack);

        verify(workerService).process(record.value());
        verify(ack).acknowledge();
    }

    // =========================================================
    // HELPERS
    // =========================================================

    private ConsumerRecord<String, TaskEvent> buildRecord(TaskEventType type) {
        UUID taskId = UUID.randomUUID();
        TaskEvent event = new TaskEvent(
                UUID.randomUUID().toString(), type, taskId,
                "test-task", "EMAIL_SEND", TaskStatus.PENDING,
                5, Instant.now(), Instant.now(), 0
        );
        return new ConsumerRecord<>("task-events", 0, 0L, taskId.toString(), event);
    }
}