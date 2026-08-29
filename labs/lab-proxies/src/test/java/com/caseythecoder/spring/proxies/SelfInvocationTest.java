package com.caseythecoder.spring.proxies;

import java.util.concurrent.atomic.AtomicInteger;

import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;

import org.springframework.aop.framework.AopContext;
import org.springframework.aop.framework.ProxyFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The single most consequential fact about Spring AOP: advice lives on the proxy, so a call from
 * one method of your bean to another goes straight to the target and skips it.
 *
 * <p>This is not a transaction bug, a caching bug and a retry bug. It is one bug, and it is here.
 *
 * <p>Notes: docs/proxies.md, "Self-invocation".
 */
class SelfInvocationTest {

    @Test
    void anInternalCallSkipsTheAdviceEntirely() {
        AtomicInteger advised = new AtomicInteger();
        Orders proxy = proxy(new Orders(), advised, false);

        proxy.placeTwo();

        assertThat(advised.get())
                .as("placeTwo was advised; the two place() calls it made were not")
                .isEqualTo(1);
    }

    @Test
    void callingTheSameMethodFromOutsideIsAdvisedEveryTime() {
        AtomicInteger advised = new AtomicInteger();
        Orders proxy = proxy(new Orders(), advised, false);

        proxy.place();
        proxy.place();

        assertThat(advised.get()).isEqualTo(2);
    }

    @Test
    void exposeProxyLetsTheTargetReachItsOwnProxy() {
        AtomicInteger advised = new AtomicInteger();
        SelfAware target = new SelfAware();
        SelfAware proxy = proxy(target, advised, true);

        proxy.placeTwoViaProxy();

        assertThat(advised.get())
                .as("one for placeTwoViaProxy, plus one per place() routed back through the proxy")
                .isEqualTo(3);
    }

    @Test
    void currentProxyThrowsWhenExposeProxyIsOff() {
        // The default. AopContext holds nothing, so the "fix" fails loudly rather than silently
        // doing the wrong thing, which is at least honest.
        AtomicInteger advised = new AtomicInteger();
        SelfAware proxy = proxy(new SelfAware(), advised, false);

        assertThatThrownBy(proxy::placeTwoViaProxy)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot find current proxy");
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(T target, AtomicInteger counter, boolean exposeProxy) {
        MethodInterceptor counting = invocation -> {
            counter.incrementAndGet();
            return invocation.proceed();
        };
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.setExposeProxy(exposeProxy);
        factory.addAdvice(counting);
        return (T) factory.getProxy();
    }

    static class Orders {

        public String place() {
            return "placed";
        }

        public String placeTwo() {
            // Plain Java call on this. There is no proxy involved and no way for one to get here.
            place();
            place();
            return "placed two";
        }
    }

    static class SelfAware {

        public String place() {
            return "placed";
        }

        public String placeTwoViaProxy() {
            SelfAware self = (SelfAware) AopContext.currentProxy();
            self.place();
            self.place();
            return "placed two";
        }
    }
}
