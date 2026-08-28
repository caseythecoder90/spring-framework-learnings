package com.caseythecoder.spring.events.demo;

import java.time.Duration;

import com.caseythecoder.spring.support.Sleeps;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Profile("demo")
public class OrderListeners {

    private static final Log logger = LogFactory.getLog(OrderListeners.class);

    @Order(1)
    @EventListener
    public void reserveInventory(OrderPlaced event) {
        logger.info("[" + Thread.currentThread().getName() + "] reserveInventory " + event.orderId());
        Sleeps.quietly(Duration.ofMillis(400));
    }

    @Order(2)
    @EventListener
    public void sendConfirmationEmail(OrderPlaced event) {
        logger.info("[" + Thread.currentThread().getName() + "] sendConfirmationEmail " + event.orderId());
        Sleeps.quietly(Duration.ofMillis(400));
    }
}
