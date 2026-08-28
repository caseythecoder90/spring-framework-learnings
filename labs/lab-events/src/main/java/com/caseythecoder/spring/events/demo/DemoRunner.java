package com.caseythecoder.spring.events.demo;

import java.math.BigDecimal;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("demo")
public class DemoRunner implements CommandLineRunner {

    private final OrderService orders;

    public DemoRunner(OrderService orders) {
        this.orders = orders;
    }

    @Override
    public void run(String... args) {
        orders.placeOrder("ORD-1", new BigDecimal("249.99"));
    }
}
