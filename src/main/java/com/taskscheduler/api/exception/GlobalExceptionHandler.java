package com.taskscheduler.api.exception;

import com.taskscheduler.domain.exception.TaskNotFoundException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Centralized exception → HTTP response mapping.
 *
 * Extends ResponseEntityExceptionHandler — the Spring-recommended way
 * to handle both custom and standard Spring MVC exceptions in one place.
 *
 * Why extend it?
 *   Spring Boot auto-configures a ProblemDetailsExceptionHandler ONLY when
 *   no ResponseEntityExceptionHandler bean exists in the context. By extending
 *   it here, our class IS that bean — Spring Boot's auto-configured one is
 *   suppressed via @ConditionalOnMissingBean(ResponseEntityExceptionHandler.class).
 *
 * This means our overrides always win for standard Spring MVC exceptions
 * (MethodArgumentNotValidException, HttpMessageNotReadableException, etc.)
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    // =========================================================
    // DOMAIN EXCEPTIONS
    // =========================================================

    @ExceptionHandler(TaskNotFoundException.class)
    public ProblemDetail handleTaskNotFound(TaskNotFoundException ex) {
        log.warn("Task not found: taskId={}", ex.getTaskId());

        ProblemDetail problem = ProblemDetail
                .forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Task Not Found");
        problem.setProperty("taskId", ex.getTaskId());
        return problem;
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handleIllegalState(IllegalStateException ex) {
        log.warn("Invalid state transition: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail
                .forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Invalid Task State Transition");
        return problem;
    }

    // =========================================================
    // VALIDATION EXCEPTIONS
    // =========================================================

    /**
     * @RequestBody validation failures (@Valid on a record/class).
     * Overriding this method instead of using @ExceptionHandler
     * ensures our logic wins over Spring Boot's auto-configured handler.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        Map<String, String> errors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        FieldError::getDefaultMessage,
                        (existing, replacement) -> existing
                ));

        ProblemDetail problem = ProblemDetail
                .forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
        problem.setTitle("Validation Failed");
        problem.setProperty("errors", errors);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    /**
     * @RequestParam / @PathVariable constraint violations (@Validated class-level).
     * Triggered when e.g. limit=0 is passed to getDueTasks().
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex) {
        Map<String, String> errors = ex.getConstraintViolations()
                .stream()
                .collect(Collectors.toMap(
                        v -> v.getPropertyPath().toString(),
                        v -> v.getMessage(),
                        (existing, replacement) -> existing
                ));

        ProblemDetail problem = ProblemDetail
                .forStatusAndDetail(HttpStatus.BAD_REQUEST, "Parameter validation failed");
        problem.setTitle("Validation Failed");
        problem.setProperty("errors", errors);
        return problem;
    }

    /**
     * Catch-all for any exception not explicitly handled above.
     * Logs the full stack trace server-side but returns a safe
     * generic message to clients — never leak internal details.
     *
     * We removed this in M2 when we extended ResponseEntityExceptionHandler.
     * Adding it back with @Order(Ordered.HIGHEST_PRECEDENCE) to ensure
     * it overrides the parent's generic handling.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneric(Exception ex) {
        log.error("Unhandled exception: {}", ex.getMessage(), ex);
        ProblemDetail problem = ProblemDetail
                .forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                        "An unexpected error occurred");
        problem.setTitle("Internal Server Error");
        return problem;
    }
}