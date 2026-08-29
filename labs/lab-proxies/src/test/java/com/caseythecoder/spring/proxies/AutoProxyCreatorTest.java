package com.caseythecoder.spring.proxies;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import org.springframework.aop.Advisor;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.framework.autoproxy.DefaultAdvisorAutoProxyCreator;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.NameMatchMethodPointcut;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How proxies actually get created in a running container: an auto-proxy creator, which is just a
 * {@code BeanPostProcessor} that returns a different object from
 * {@code postProcessAfterInitialization}.
 *
 * <p>Once that clicks, the ordering rules elsewhere in Spring make sense. A post-processor with
 * lower precedence than the auto-proxy creator receives the proxy; one with higher precedence
 * receives the raw bean. That is exactly why {@code ScheduledAnnotationBeanPostProcessor} declares
 * {@code LOWEST_PRECEDENCE}.
 *
 * <p>Notes: docs/proxies.md, "Who creates the proxy".
 */
@SpringJUnitConfig(AutoProxyCreatorTest.Config.class)
class AutoProxyCreatorTest {

    @Autowired
    Greeter greeter;

    @Autowired
    Config config;

    @Test
    void theBeanYouGetInjectedIsAProxyNotTheClassYouWrote() {
        assertThat(AopUtils.isAopProxy(greeter)).isTrue();
        assertThat(greeter.getClass()).isNotEqualTo(Greeter.class);
        assertThat(AopProxyUtils.ultimateTargetClass(greeter)).isEqualTo(Greeter.class);
    }

    @Test
    void theAdvisorRunsForMatchingMethodsOnly() {
        config.advised.set(0);

        greeter.greet();
        assertThat(config.advised.get()).isEqualTo(1);

        greeter.ignored();
        assertThat(config.advised.get())
                .as("the pointcut matches greet, not ignored")
                .isEqualTo(1);
    }

    @Test
    void bootForcesClassProxyingByDefault() {
        // AopAutoConfiguration has two branches. With AspectJ on the classpath it applies
        // @EnableAspectJAutoProxy(proxyTargetClass = true); without it (this module) it contributes
        // a BeanFactoryPostProcessor that flips the auto-proxy creator's proxyTargetClass flag.
        // Either way the default is CGLIB, which is why Boot beans are class proxies even when
        // they implement an interface.
        //
        // Both branches key off @ConditionalOnBooleanProperty(matchIfMissing = true), so the
        // presence of this bean is the observable default rather than a value read back.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class))
                .run(context -> assertThat(context)
                        .hasBean("forceAutoProxyCreatorToUseClassProxying"));
    }

    @Test
    void turningProxyTargetClassOffRemovesThatForcing() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class))
                .withPropertyValues("spring.aop.proxy-target-class=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean("forceAutoProxyCreatorToUseClassProxying"));
    }

    @Test
    void turningAopOffEntirelyRemovesTheAutoConfiguration() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class))
                .withPropertyValues("spring.aop.auto=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean("forceAutoProxyCreatorToUseClassProxying"));
    }

    @Configuration(proxyBeanMethods = false)
    static class Config {

        final AtomicInteger advised = new AtomicInteger();

        @Bean
        Greeter greeter() {
            return new Greeter();
        }

        /** A BeanPostProcessor. That is the whole mechanism. */
        @Bean
        DefaultAdvisorAutoProxyCreator autoProxyCreator() {
            DefaultAdvisorAutoProxyCreator creator = new DefaultAdvisorAutoProxyCreator();
            creator.setProxyTargetClass(true);
            return creator;
        }

        @Bean
        Advisor greetAdvisor() {
            NameMatchMethodPointcut pointcut = new NameMatchMethodPointcut();
            pointcut.addMethodName("greet");

            DefaultPointcutAdvisor advisor = new DefaultPointcutAdvisor(pointcut, (org.aopalliance.intercept.MethodInterceptor) invocation -> {
                advised.incrementAndGet();
                return invocation.proceed();
            });
            advisor.setOrder(Ordered.HIGHEST_PRECEDENCE);
            return advisor;
        }
    }

    static class Greeter {

        public String greet() {
            return "hello";
        }

        public String ignored() {
            return "ignored";
        }
    }
}
