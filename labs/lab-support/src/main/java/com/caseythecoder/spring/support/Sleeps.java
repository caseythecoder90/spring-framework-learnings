package com.caseythecoder.spring.support;

import java.time.Duration;

/** Sleeping without the {@code try/catch} noise, so the labs stay readable. */
public final class Sleeps {

    private Sleeps() {
    }

    /** Sleeps for {@code duration}, restoring the interrupt flag rather than swallowing it. */
    public static void quietly(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    public static void millis(long millis) {
        quietly(Duration.ofMillis(millis));
    }
}
