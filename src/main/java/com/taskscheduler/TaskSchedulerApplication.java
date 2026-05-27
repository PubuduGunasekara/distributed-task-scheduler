package com.taskscheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

@Slf4j
@SpringBootApplication
public class TaskSchedulerApplication {

	public static void main(String[] args) {
		SpringApplication.run(TaskSchedulerApplication.class, args);
	}

	@EventListener(ApplicationReadyEvent.class)
	public void onApplicationReady() {
		log.info("================================================");
		log.info("  Distributed Task Scheduler started");
		log.info("  API      → http://localhost:8080/api/v1/status");
		log.info("  Health   → http://localhost:8080/actuator/health");
		log.info("  Metrics  → http://localhost:8080/actuator/prometheus");
		log.info("  Grafana  → http://localhost:3000");
		log.info("================================================");
	}
}