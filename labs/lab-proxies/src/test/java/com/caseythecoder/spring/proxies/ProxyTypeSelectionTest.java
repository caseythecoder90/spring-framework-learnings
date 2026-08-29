package com.caseythecoder.spring.proxies;

import java.lang.reflect.Proxy;

import org.junit.jupiter.api.Test;

import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which kind of proxy you get, decided in one readable method:
 * {@code DefaultAopProxyFactory.createAopProxy}.
 *
 * <pre>
 * if (optimize || proxyTargetClass || no user-supplied interfaces) {
 *     return targetClass.isInterface() ? JdkDynamicAopProxy : ObjenesisCglibAopProxy;
 * }
 * return JdkDynamicAopProxy;
 * </pre>
 *
 * <p>It matters because the two kinds fail differently: a JDK proxy can only be cast to its
 * interfaces, and a CGLIB proxy cannot intercept final or private methods.
 *
 * <p>Notes: docs/proxies.md, "Which proxy you get".
 */
class ProxyTypeSelectionTest {

    @Test
    void anInterfaceWithoutProxyTargetClassGivesAJdkProxy() {
        ProxyFactory factory = new ProxyFactory(new OrderServiceImpl());
        factory.setInterfaces(OrderService.class);

        Object proxy = factory.getProxy();

        assertThat(AopUtils.isJdkDynamicProxy(proxy)).isTrue();
        assertThat(Proxy.isProxyClass(proxy.getClass())).isTrue();
        assertThat(proxy).isInstanceOf(OrderService.class);
        assertThat(proxy)
                .as("a JDK proxy is not an instance of the implementation class")
                .isNotInstanceOf(OrderServiceImpl.class);
    }

    @Test
    void proxyTargetClassForcesCglibEvenWhenAnInterfaceExists() {
        ProxyFactory factory = new ProxyFactory(new OrderServiceImpl());
        factory.setInterfaces(OrderService.class);
        factory.setProxyTargetClass(true);

        Object proxy = factory.getProxy();

        assertThat(AopUtils.isCglibProxy(proxy)).isTrue();
        assertThat(proxy)
                .as("CGLIB subclasses the target, so both types work")
                .isInstanceOf(OrderServiceImpl.class)
                .isInstanceOf(OrderService.class);
    }

    @Test
    void aClassWithNoInterfacesAlwaysGivesCglib() {
        ProxyFactory factory = new ProxyFactory(new StandaloneService());

        Object proxy = factory.getProxy();

        assertThat(AopUtils.isCglibProxy(proxy)).isTrue();
    }

    @Test
    void aProxiedClassIsNotTheClassYouWrote() {
        ProxyFactory factory = new ProxyFactory(new StandaloneService());
        Object proxy = factory.getProxy();

        assertThat(proxy.getClass()).isNotEqualTo(StandaloneService.class);
        assertThat(proxy.getClass().getName())
                .as("CGLIB names its subclasses after the target")
                .contains("StandaloneService")
                .contains("$$");

        // Which is why anything wanting the real type has to ask, rather than call getClass().
        assertThat(AopProxyUtils.ultimateTargetClass(proxy)).isEqualTo(StandaloneService.class);
    }

    @Test
    void isAopProxyCoversBothKinds() {
        ProxyFactory jdk = new ProxyFactory(new OrderServiceImpl());
        jdk.setInterfaces(OrderService.class);
        ProxyFactory cglib = new ProxyFactory(new StandaloneService());

        assertThat(AopUtils.isAopProxy(jdk.getProxy())).isTrue();
        assertThat(AopUtils.isAopProxy(cglib.getProxy())).isTrue();
        assertThat(AopUtils.isAopProxy(new StandaloneService())).isFalse();
    }

    interface OrderService {

        String place();
    }

    static class OrderServiceImpl implements OrderService {

        @Override
        public String place() {
            return "placed";
        }
    }

    static class StandaloneService {

        public String work() {
            return "worked";
        }
    }
}
