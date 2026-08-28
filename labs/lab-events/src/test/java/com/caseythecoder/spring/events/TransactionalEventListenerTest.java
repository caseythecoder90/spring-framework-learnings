package com.caseythecoder.spring.events;

import javax.sql.DataSource;

import com.caseythecoder.spring.support.Recorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @TransactionalEventListener defers the callback to a transaction synchronization instead of
 * running it inline. The three behaviours worth internalising:
 *
 * <ol>
 * <li>it runs AFTER the commit, so the writing method has already returned;
 * <li>a rollback means it never runs, which is usually the point;
 * <li>with no transaction in progress it is silently skipped - the only trace is a DEBUG log.
 * </ol>
 *
 * <p>That third one is the production bug: the same listener works from a @Transactional service
 * and quietly does nothing when called from a scheduled job or a test that forgot the transaction.
 *
 * <p>Notes: docs/events.md, "Transactional listeners".
 */
@SpringJUnitConfig(TransactionalEventListenerTest.Config.class)
class TransactionalEventListenerTest {

    @Autowired
    Recorder recorder;

    @Autowired
    OrderService orders;

    @BeforeEach
    void reset() {
        recorder.clear();
    }

    @Test
    void aPlainListenerRunsInsideTheTransactionAndACommitListenerRunsAfterIt() {
        orders.placeOrder(false);

        // Two AFTER_COMMIT listeners with no @Order between them run in an unspecified order, so
        // only the sequence that Spring actually guarantees is asserted here.
        assertThat(recorder.labels())
                .containsSubsequence("service-begin", "plain[inTransaction=true]", "service-end", "after-commit");
        assertThat(recorder.labels()).contains("fallback");
    }

    @Test
    void aRollbackSkipsTheCommitListenerAndTriggersTheRollbackOne() {
        assertThatThrownBy(() -> orders.placeOrder(true)).isInstanceOf(IllegalStateException.class);

        assertThat(recorder.labels()).contains("plain[inTransaction=true]", "after-rollback");
        assertThat(recorder.labels()).doesNotContain("after-commit");
    }

    @Test
    void withNoTransactionTheCommitListenerIsSilentlySkipped() {
        orders.placeOrderWithoutTransaction();

        assertThat(recorder.labels()).contains("plain[inTransaction=false]");
        assertThat(recorder.labels())
                .as("no transaction to hang a synchronization on, so the listener never fires")
                .doesNotContain("after-commit");
        assertThat(recorder.labels())
                .as("fallbackExecution = true is the opt-in that makes it run anyway")
                .contains("fallback");
    }

    record OrderPlaced(String id) {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Config {

        @Bean
        Recorder recorder() {
            return new Recorder();
        }

        @Bean
        Listeners listeners(Recorder recorder) {
            return new Listeners(recorder);
        }

        @Bean
        OrderService orderService(ApplicationEventPublisher publisher, Recorder recorder) {
            return new OrderService(publisher, recorder);
        }

        @Bean
        DataSource dataSource() {
            // Nothing is ever written; the labs only need a real transaction to synchronize on.
            return new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .generateUniqueName(true)
                    .build();
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }

    static class OrderService {

        private final ApplicationEventPublisher publisher;

        private final Recorder recorder;

        OrderService(ApplicationEventPublisher publisher, Recorder recorder) {
            this.publisher = publisher;
            this.recorder = recorder;
        }

        @Transactional
        public void placeOrder(boolean fail) {
            recorder.record("service-begin");
            publisher.publishEvent(new OrderPlaced("o-1"));
            recorder.record("service-end");
            if (fail) {
                throw new IllegalStateException("order rejected");
            }
        }

        public void placeOrderWithoutTransaction() {
            publisher.publishEvent(new OrderPlaced("o-2"));
        }
    }

    static class Listeners {

        private final Recorder recorder;

        Listeners(Recorder recorder) {
            this.recorder = recorder;
        }

        @EventListener
        public void plain(OrderPlaced event) {
            recorder.record("plain[inTransaction="
                    + TransactionSynchronizationManager.isActualTransactionActive() + "]");
        }

        @TransactionalEventListener
        public void afterCommit(OrderPlaced event) {
            recorder.record("after-commit");
        }

        @TransactionalEventListener(phase = TransactionPhase.AFTER_ROLLBACK)
        public void afterRollback(OrderPlaced event) {
            recorder.record("after-rollback");
        }

        @TransactionalEventListener(fallbackExecution = true)
        public void fallback(OrderPlaced event) {
            recorder.record("fallback");
        }
    }
}
