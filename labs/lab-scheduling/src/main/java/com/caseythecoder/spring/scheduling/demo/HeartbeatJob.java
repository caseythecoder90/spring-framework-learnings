package com.caseythecoder.spring.scheduling.demo;

import java.time.LocalTime;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Fast, harmless job. Its job is to visibly stop ticking when {@link SlowNeighbourJob} runs. */
@Component
@Profile("demo")
public class HeartbeatJob {

    private static final Log logger = LogFactory.getLog(HeartbeatJob.class);

    @Scheduled(fixedRate = 500)
    public void tick() {
        logger.info("heartbeat at " + LocalTime.now().withNano(0) + " on " + Thread.currentThread().getName());
    }
}
