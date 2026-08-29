package com.caseythecoder.spring.testing;

import java.util.List;
import javax.sql.DataSource;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.annotation.Commit;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @Transactional} on a test class means something different from {@code @Transactional} on a
 * service: the test itself runs inside a transaction that is <strong>rolled back at the end</strong>.
 *
 * <p>That is a very good default - the database is clean after every test with no teardown code -
 * and it quietly changes the behaviour of anything that cares about commits. The
 * {@code @TransactionalEventListener} test at the bottom is the one that costs people an afternoon.
 *
 * <p>Notes: docs/testing.md, "Transactional tests".
 */
@SpringJUnitConfig(TransactionalTestTest.Config.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Transactional
class TransactionalTestTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    Listeners listeners;

    @Test
    @Order(1)
    void theTestMethodItselfRunsInsideATransaction() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
    }

    @Test
    @Order(2)
    void aRowWrittenHereIsVisibleWithinTheTest() {
        jdbc.update("insert into item(id) values ('rolled-back')");

        assertThat(ids()).containsExactly("rolled-back");
    }

    @Test
    @Order(3)
    void andIsGoneByTheNextTestBecauseTheTransactionWasRolledBack() {
        assertThat(ids())
                .as("no teardown code, no leftover state, no ordering coupling between tests")
                .isEmpty();
    }

    @Test
    @Order(4)
    @Commit
    void commitOptsOutOfTheRollbackForOneMethod() {
        jdbc.update("insert into item(id) values ('committed')");
    }

    @Test
    @Order(5)
    void andThatOneReallyDidStay() {
        assertThat(ids()).containsExactly("committed");
        jdbc.update("delete from item");
    }

    @Test
    @Order(6)
    void anAfterCommitListenerNeverFiresInARolledBackTest() {
        // The test transaction rolls back, so AFTER_COMMIT never happens, so the listener never
        // runs - and a test that was meant to prove the listener works passes without ever
        // calling it. The fixes are @Commit, TestTransaction.flagForCommit(), or testing the
        // listener directly rather than through the publisher.
        listeners.fired.clear();

        publisher.publishEvent(new OrderPlaced("o-1"));

        assertThat(listeners.fired)
                .as("the plain listener ran, the after-commit one did not")
                .containsExactly("@EventListener");
    }

    @Test
    @Order(7)
    @Commit
    void andDoesFireOnceTheTestTransactionCommits() {
        listeners.fired.clear();

        publisher.publishEvent(new OrderPlaced("o-2"));

        assertThat(listeners.fired).containsExactly("@EventListener");
        // AFTER_COMMIT arrives after this method returns, so it cannot be asserted here. See
        // theAfterCommitListenerRanAfterTheMethodReturned below.
    }

    @Test
    @Order(8)
    void theAfterCommitListenerRanAfterTheMethodReturned() {
        assertThat(listeners.fired)
                .as("appended once the previous test's transaction committed")
                .containsExactly("@EventListener", "AFTER_COMMIT");
    }

    private List<String> ids() {
        return jdbc.queryForList("select id from item order by id", String.class);
    }

    // ---------------------------------------------------------------------------------------

    record OrderPlaced(String id) {
    }

    static class Listeners {

        final List<String> fired = new java.util.concurrent.CopyOnWriteArrayList<>();

        @org.springframework.context.event.EventListener
        void onAnyEvent(OrderPlaced event) {
            fired.add("@EventListener");
        }

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        void afterCommit(OrderPlaced event) {
            fired.add("AFTER_COMMIT");
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Config {

        @Bean
        DataSource dataSource() {
            return new EmbeddedDatabaseBuilder()
                    .generateUniqueName(true)
                    .setType(EmbeddedDatabaseType.H2)
                    .addScript("classpath:schema.sql")
                    .build();
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new JdbcTransactionManager(dataSource);
        }

        @Bean
        Listeners listeners() {
            return new Listeners();
        }
    }
}
