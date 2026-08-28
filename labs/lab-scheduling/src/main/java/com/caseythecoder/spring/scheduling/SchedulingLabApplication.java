package com.caseythecoder.spring.scheduling;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Runnable companion to {@code docs/scheduling.md}.
 *
 * <p>The demo jobs are behind the {@code demo} profile so that they do not fire during tests:
 * <pre>{@code ./mvnw -pl labs/lab-scheduling spring-boot:run -Dspring-boot.run.profiles=demo}</pre>
 *
 * <p>Watch the thread names in the log. With the default pool of one thread, the heartbeat stalls
 * for as long as the slow job holds the thread — that is the whole lesson, live.
 */
@SpringBootApplication
@EnableScheduling
public class SchedulingLabApplication {

    public static void main(String[] args) {
        SpringApplication.run(SchedulingLabApplication.class, args);
    }
}
