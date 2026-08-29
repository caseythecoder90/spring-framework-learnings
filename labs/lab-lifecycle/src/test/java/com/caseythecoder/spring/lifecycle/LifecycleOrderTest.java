package com.caseythecoder.spring.lifecycle;

import java.util.List;

import com.caseythecoder.spring.support.Recorder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.junit.jupiter.api.Test;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every lifecycle callback a single bean can receive, recorded in the order the container actually
 * fires them. Written by running it and reading the output, not from memory.
 *
 * <p>The ordering that matters most in practice is the last two: the auto-proxy creator runs in
 * {@code postProcessAfterInitialization}, so anything happening before that point sees the raw
 * object, and {@code @PostConstruct} runs before the bean has been proxied.
 *
 * <p>Notes: docs/bean-lifecycle.md, "The order".
 */
class LifecycleOrderTest {

    @Test
    void everyCallbackFiresInThisOrder() {
        Recorder recorder = new Recorder();
        Config.recorder = recorder;

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(Config.class);
            context.refresh();
            recorder.record("--- context refreshed ---");
        }

        assertThat(recorder.labels()).containsExactly(
                "bpp:beforeInstantiation",
                "constructor",
                "bpp:mergedBeanDefinition",
                "bpp:afterInstantiation",
                // The setter runs first because injection IS a postProcessProperties callback, on
                // AutowiredAnnotationBeanPostProcessor. That one is PriorityOrdered; the recorder
                // in this test is unordered, so it lands afterwards. Ordering between two
                // BeanPostProcessors is the whole reason this pair looks backwards.
                "setter",
                "bpp:properties",
                "aware:BeanName",
                "aware:BeanClassLoader",
                "aware:BeanFactory",
                "aware:Environment",
                "aware:ApplicationContext",
                "@PostConstruct",
                "bpp:beforeInitialization",
                "afterPropertiesSet",
                "custom init-method",
                "bpp:afterInitialization",
                "afterSingletonsInstantiated",
                "--- context refreshed ---",
                "@PreDestroy",
                "destroy",
                "custom destroy-method");
    }

    @Test
    void awareCallbacksAllArriveBeforePostConstruct() {
        Recorder recorder = new Recorder();
        Config.recorder = recorder;
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(Config.class);
            context.refresh();
        }

        List<String> labels = recorder.labels();
        assertThat(labels.indexOf("aware:ApplicationContext")).isLessThan(labels.indexOf("@PostConstruct"));
    }

    @Test
    void injectionIsCompleteBeforeAnyInitialisationCallback() {
        // The practical rule: never touch a dependency in a constructor if it was field-injected,
        // and do use @PostConstruct for anything that needs the collaborators.
        Recorder recorder = new Recorder();
        Config.recorder = recorder;
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(Config.class);
            context.refresh();
        }

        List<String> labels = recorder.labels();
        assertThat(labels.indexOf("setter")).isLessThan(labels.indexOf("@PostConstruct"));
        assertThat(labels.indexOf("setter")).isLessThan(labels.indexOf("aware:BeanName"));
        assertThat(labels.indexOf("bpp:properties")).isLessThan(labels.indexOf("aware:BeanName"));
    }

    @Configuration(proxyBeanMethods = false)
    static class Config {

        static Recorder recorder = new Recorder();

        @Bean(initMethod = "customInit", destroyMethod = "customDestroy")
        Subject subject() {
            return new Subject(recorder);
        }

        @Bean
        Collaborator collaborator() {
            return new Collaborator();
        }

        @Bean
        static RecordingBeanPostProcessor recordingBeanPostProcessor() {
            return new RecordingBeanPostProcessor();
        }

        @Bean
        SmartInitializingSingleton smartInitializingSingleton() {
            return () -> recorder.record("afterSingletonsInstantiated");
        }
    }

    static class Collaborator {
    }

    /** Watches only the Subject bean, so the log stays readable. */
    static class RecordingBeanPostProcessor
            implements InstantiationAwareBeanPostProcessor, BeanPostProcessor,
            org.springframework.beans.factory.support.MergedBeanDefinitionPostProcessor {

        private static void record(String label) {
            Config.recorder.record(label);
        }

        @Override
        public Object postProcessBeforeInstantiation(Class<?> beanClass, String beanName) {
            if (beanClass == Subject.class) {
                record("bpp:beforeInstantiation");
            }
            return null;
        }

        @Override
        public void postProcessMergedBeanDefinition(
                org.springframework.beans.factory.support.RootBeanDefinition beanDefinition,
                Class<?> beanType, String beanName) {
            if (beanType == Subject.class) {
                record("bpp:mergedBeanDefinition");
            }
        }

        @Override
        public boolean postProcessAfterInstantiation(Object bean, String beanName) {
            if (bean instanceof Subject) {
                record("bpp:afterInstantiation");
            }
            return true;
        }

        @Override
        public org.springframework.beans.PropertyValues postProcessProperties(
                org.springframework.beans.PropertyValues pvs, Object bean, String beanName) {
            if (bean instanceof Subject) {
                record("bpp:properties");
            }
            return pvs;
        }

        @Override
        public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
            if (bean instanceof Subject) {
                record("bpp:beforeInitialization");
            }
            return bean;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
            if (bean instanceof Subject) {
                record("bpp:afterInitialization");
            }
            return bean;
        }
    }

    static class Subject implements BeanNameAware, BeanClassLoaderAware, BeanFactoryAware,
            EnvironmentAware, ApplicationContextAware, InitializingBean, DisposableBean {

        private final Recorder recorder;

        @SuppressWarnings("unused")
        private Collaborator collaborator;

        Subject(Recorder recorder) {
            this.recorder = recorder;
            recorder.record("constructor");
        }

        @org.springframework.beans.factory.annotation.Autowired
        void setCollaborator(Collaborator collaborator) {
            this.collaborator = collaborator;
            recorder.record("setter");
        }

        @Override
        public void setBeanName(String name) {
            recorder.record("aware:BeanName");
        }

        @Override
        public void setBeanClassLoader(ClassLoader classLoader) {
            recorder.record("aware:BeanClassLoader");
        }

        @Override
        public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
            recorder.record("aware:BeanFactory");
        }

        @Override
        public void setEnvironment(Environment environment) {
            recorder.record("aware:Environment");
        }

        @Override
        public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
            recorder.record("aware:ApplicationContext");
        }

        @PostConstruct
        void postConstruct() {
            recorder.record("@PostConstruct");
        }

        @Override
        public void afterPropertiesSet() {
            recorder.record("afterPropertiesSet");
        }

        void customInit() {
            recorder.record("custom init-method");
        }

        @PreDestroy
        void preDestroy() {
            recorder.record("@PreDestroy");
        }

        @Override
        public void destroy() {
            recorder.record("destroy");
        }

        void customDestroy() {
            recorder.record("custom destroy-method");
        }
    }
}
