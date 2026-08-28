package com.caseythecoder.spring.events.demo;

import java.math.BigDecimal;

/**
 * A plain record, not an {@code ApplicationEvent} subclass. Spring wraps it in a
 * {@code PayloadApplicationEvent<OrderPlaced>} on the way out — see docs/events.md.
 */
public record OrderPlaced(String orderId, BigDecimal total) {
}
