package com.caseythecoder.spring.proxies;

import java.util.concurrent.atomic.AtomicInteger;

import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;

import org.springframework.aop.framework.ProxyFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A CGLIB proxy is a subclass of your class, instantiated by Objenesis <em>without calling any
 * constructor</em>. Its own fields are therefore never assigned, and it delegates method calls to a
 * separate target instance that does have them.
 *
 * <p>This is why reading a field off an injected bean sometimes gives null while calling a getter
 * on the same reference works. It cost this repo a confusing hour in the events lab.
 *
 * <p>Notes: docs/proxies.md, "What a proxy does not carry over".
 */
class CglibLimitationsTest {

    @Test
    void theProxyConstructorIsNeverCalled() {
        Counted.instances.set(0);
        Counted target = new Counted();
        assertThat(Counted.instances.get()).isEqualTo(1);

        ProxyFactory factory = new ProxyFactory(target);
        factory.addAdvice(noop());
        Counted proxy = (Counted) factory.getProxy();

        assertThat(proxy).isNotSameAs(target);
        assertThat(Counted.instances.get())
                .as("Objenesis allocates the subclass without running a constructor")
                .isEqualTo(1);
    }

    @Test
    void fieldsReadOffTheProxyAreUnsetButGettersWork() {
        ProxyFactory factory = new ProxyFactory(new Counted());
        factory.addAdvice(noop());
        Counted proxy = (Counted) factory.getProxy();

        assertThat(proxy.name)
                .as("the proxy's own field was never assigned, because no constructor ran")
                .isNull();
        assertThat(proxy.getName())
                .as("the call is delegated to the target, which was constructed normally")
                .isEqualTo("configured");
    }

    @Test
    void finalMethodsAreNotIntercepted() {
        AtomicInteger advised = new AtomicInteger();
        ProxyFactory factory = new ProxyFactory(new Counted());
        factory.addAdvice(counting(advised));
        Counted proxy = (Counted) factory.getProxy();

        proxy.overridable();
        assertThat(advised.get()).isEqualTo(1);

        advised.set(0);
        proxy.notOverridable();
        assertThat(advised.get())
                .as("CGLIB cannot override a final method, so there is nowhere to put the advice")
                .isZero();
    }

    @Test
    void aFinalMethodOnAProxyAlsoSeesTheProxysEmptyState() {
        // The consequence people actually hit: the method runs, but on the proxy instance, whose
        // fields are unset. A final getter returns null and nothing warns you.
        ProxyFactory factory = new ProxyFactory(new Counted());
        factory.addAdvice(noop());
        Counted proxy = (Counted) factory.getProxy();

        assertThat(proxy.finalName()).isNull();
        assertThat(proxy.getName()).isEqualTo("configured");
    }

    private static MethodInterceptor noop() {
        return org.aopalliance.intercept.MethodInvocation::proceed;
    }

    private static MethodInterceptor counting(AtomicInteger counter) {
        return invocation -> {
            counter.incrementAndGet();
            return invocation.proceed();
        };
    }

    static class Counted {

        static final AtomicInteger instances = new AtomicInteger();

        final String name;

        Counted() {
            instances.incrementAndGet();
            this.name = "configured";
        }

        public String getName() {
            return name;
        }

        public String overridable() {
            return "overridable";
        }

        public final String notOverridable() {
            return "final";
        }

        public final String finalName() {
            return name;
        }
    }
}
