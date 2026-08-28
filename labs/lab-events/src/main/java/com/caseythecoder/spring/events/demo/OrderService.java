package com.caseythecoder.spring.events.demo;

import java.math.BigDecimal;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Note the constructor injection of {@link ApplicationEventPublisher}. There is no bean definition
 * for it anywhere — {@code AbstractApplicationContext.prepareBeanFactory} calls
 * {@code registerResolvableDependency(ApplicationEventPublisher.class, this)}, so the context
 * injects itself.
 */
@Service
@Profile("demo")
public class OrderService {

    private static final Log logger = LogFactory.getLog(OrderService.class);

    private final ApplicationEventPublisher events;

    public OrderService(ApplicationEventPublisher events) {
        this.events = events;
    }

    public void placeOrder(String orderId, BigDecimal total) {
        logger.info("[" + Thread.currentThread().getName() + "] placeOrder BEGIN " + orderId);
        events.publishEvent(new OrderPlaced(orderId, total));
        logger.info("[" + Thread.currentThread().getName() + "] placeOrder END   " + orderId
                + " <- note this line prints AFTER every listener finished");
    }
}
