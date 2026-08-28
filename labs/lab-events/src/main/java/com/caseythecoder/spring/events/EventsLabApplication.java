package com.caseythecoder.spring.events;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Runnable companion to {@code docs/events.md}.
 *
 * <pre>{@code ./mvnw -pl labs/lab-events spring-boot:run -Dspring-boot.run.profiles=demo}</pre>
 *
 * <p>The demo prints the thread name at every hop. Everything runs on {@code main} — publishing an
 * event is a method call, not a message queue.
 */
@SpringBootApplication
public class EventsLabApplication {

    public static void main(String[] args) {
        SpringApplication.run(EventsLabApplication.class, args);
    }
}
