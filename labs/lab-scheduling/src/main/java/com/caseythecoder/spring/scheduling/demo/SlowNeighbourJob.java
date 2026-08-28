package com.caseythecoder.spring.scheduling.demo;

import java.time.Duration;

import com.caseythecoder.spring.support.Sleeps;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The job that ruins everyone's day. It is not misbehaving — it just takes four seconds, and the
 * default scheduler has exactly one thread to give.
 */
@Component
@Profile("demo")
public class SlowNeighbourJob {

    private static final Log logger = LogFactory.getLog(SlowNeighbourJob.class);

    @Scheduled(fixedDelay = 3000, initialDelay = 2000)
    public void slowWork() {
        logger.warn(">>> slow job START on " + Thread.currentThread().getName());
        Sleeps.quietly(Duration.ofSeconds(4));
        logger.warn("<<< slow job END   on " + Thread.currentThread().getName());
    }
}
