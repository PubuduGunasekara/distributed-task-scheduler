package com.taskscheduler.infrastructure.kafka;

import com.taskscheduler.config.KafkaTopicConfig;
import static org.assertj.core.api.Assertions.assertThatCode;
import com.taskscheduler.domain.event.TaskEvent;
import com.taskscheduler.domain.event.TaskEventType;
import com.taskscheduler.domain.model.Task;
import com.taskscheduler.domain.model.TaskStatus;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TaskEventPublisher")
class TaskEventPublisherTest {

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @InjectMocks
    private TaskEventPublisher publisher;

    @Test
    @DisplayName("should publish to task-events topic")
    void shouldPublishToCorrectTopic() {
        Task task = mockTask();
        stubKafkaSend();

        publisher.publish(task, TaskEventType.TASK_CREATED);

        verify(kafkaTemplate).send(
                eq("task-events"),
                anyString(),
                any(TaskEvent.class)
        );
    }

    @Test
    @DisplayName("should use taskId as partition key for ordered delivery")
    void shouldUseTaskIdAsPartitionKey() {
        Task task = mockTask();
        UUID taskId = task.getId();
        stubKafkaSend();

        publisher.publish(task, TaskEventType.TASK_CREATED);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(anyString(), keyCaptor.capture(), any());

        assertThat(keyCaptor.getValue()).isEqualTo(taskId.toString());
    }

    @Test
    @DisplayName("should include correct event type in the event payload")
    void shouldIncludeCorrectEventType() {
        Task task = mockTask();
        stubKafkaSend();

        publisher.publish(task, TaskEventType.TASK_COMPLETED);

        ArgumentCaptor<TaskEvent> eventCaptor = ArgumentCaptor.forClass(TaskEvent.class);
        verify(kafkaTemplate).send(anyString(), anyString(), eventCaptor.capture());

        TaskEvent captured = eventCaptor.getValue();
        assertThat(captured.eventType()).isEqualTo(TaskEventType.TASK_COMPLETED);
        assertThat(captured.taskId()).isEqualTo(task.getId());
        assertThat(captured.eventId()).isNotNull();
    }

    @Test
    @DisplayName("should not throw when Kafka send fails")
    void shouldNotThrowWhenKafkaFails() {
        Task task = mockTask();
        CompletableFuture<SendResult<String, Object>> failedFuture =
                CompletableFuture.failedFuture(new RuntimeException("Kafka unavailable"));
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(failedFuture);

        // Should complete without throwing — failure is logged, not rethrown
        org.assertj.core.api.Assertions.assertThatCode(
                () -> publisher.publish(task, TaskEventType.TASK_CREATED)
        ).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should publish dead letter to DLQ topic")
    void shouldPublishDeadLetterToDlqTopic() {
        Task task = mockTask();
        stubKafkaSend();

        publisher.publishDeadLetter(task);

        verify(kafkaTemplate).send(
                eq(KafkaTopicConfig.TASK_DLQ_TOPIC),
                anyString(),
                any(TaskEvent.class)
        );
    }

    @Test
    @DisplayName("should not throw when dead letter Kafka send fails")
    void shouldNotThrowWhenDeadLetterKafkaFails() {
        Task task = mockTask();
        CompletableFuture<SendResult<String, Object>> failedFuture =
                CompletableFuture.failedFuture(new RuntimeException("Kafka down"));
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(failedFuture);

        assertThatCode(() -> publisher.publishDeadLetter(task))
                .doesNotThrowAnyException();
    }

    // =========================================================
    // HELPERS
    // =========================================================

    private Task mockTask() {
        Task task = mock(Task.class);
        when(task.getId()).thenReturn(UUID.randomUUID());
        when(task.getName()).thenReturn("test-task");
        when(task.getType()).thenReturn("EMAIL_SEND");
        when(task.getStatus()).thenReturn(TaskStatus.PENDING);
        when(task.getPriority()).thenReturn(5);
        when(task.getScheduledAt()).thenReturn(Instant.now().plusSeconds(60));
        when(task.getRetryCount()).thenReturn(0);
        return task;
    }

    @SuppressWarnings("unchecked")
    private void stubKafkaSend() {
        RecordMetadata metadata = new RecordMetadata(
                new TopicPartition("task-events", 0), 0L, 0, 0L, 0, 0
        );
        SendResult<String, Object> sendResult = mock(SendResult.class);
        when(sendResult.getRecordMetadata()).thenReturn(metadata);

        CompletableFuture<SendResult<String, Object>> future =
                CompletableFuture.completedFuture(sendResult);
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(future);
    }
}