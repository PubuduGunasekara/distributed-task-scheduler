package com.taskscheduler.domain.port;

import com.taskscheduler.domain.event.TaskEventType;
import com.taskscheduler.domain.model.Task;

/**
 * Port (interface) through which the domain publishes task events.
 *
 * The domain layer defines WHAT it needs (publish an event).
 * The infrastructure layer decides HOW (Kafka, SQS, RabbitMQ, etc.).
 *
 * TaskService depends on this interface — not on KafkaTemplate,
 * not on any infrastructure class. This means:
 *   - TaskService is fully testable without Kafka running
 *   - Swapping Kafka for another broker = new implementation, zero domain changes
 *   - ArchUnit's "domain must not depend on infrastructure" rule stays intact
 *
 * This is the Dependency Inversion Principle (the D in SOLID):
 * high-level modules depend on abstractions, not concretions.
 */
public interface TaskEventPort {
    void publish(Task task, TaskEventType eventType);

    /**
     * Publish a task that has exhausted all retries to the dead letter topic.
     * Dead-lettered tasks require manual inspection and intervention.
     */
    void publishDeadLetter(Task task);
}