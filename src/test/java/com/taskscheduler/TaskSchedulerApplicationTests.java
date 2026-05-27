package com.taskscheduler;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("local")
class TaskSchedulerApplicationTests {

	@Test
	void contextLoads() {
		// Verifies the entire Spring context wires without errors.
		// This test will require Docker infrastructure to be running.
	}
}