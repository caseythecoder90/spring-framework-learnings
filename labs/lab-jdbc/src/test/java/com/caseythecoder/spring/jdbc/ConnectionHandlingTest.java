package com.caseythecoder.spring.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where connections come from, and the single reason it matters: outside a transaction every
 * {@code JdbcTemplate} call takes and returns its own connection, while inside one they all share
 * the connection bound to the thread.
 *
 * <p>That binding is done by {@code DataSourceUtils} against
 * {@code TransactionSynchronizationManager} — the same thread-local that makes a transaction fail
 * to cross an {@code @Async} boundary.
 *
 * <p>Notes: docs/jdbctemplate.md, "Where the connection comes from".
 */
@SpringJUnitConfig(ConnectionHandlingTest.Config.class)
class ConnectionHandlingTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CountingDataSource dataSource;

    @Autowired
    TransactionTemplate transactions;

    @BeforeEach
    void reset() {
        jdbc.execute("DROP TABLE IF EXISTS widget");
        jdbc.execute("CREATE TABLE widget (id VARCHAR(16) PRIMARY KEY, name VARCHAR(64))");
        dataSource.handedOut.set(0);
    }

    @Test
    void withoutATransactionEveryCallTakesItsOwnConnection() {
        jdbc.update("INSERT INTO widget (id, name) VALUES (?, ?)", "w1", "one");
        jdbc.update("INSERT INTO widget (id, name) VALUES (?, ?)", "w2", "two");
        jdbc.queryForObject("SELECT COUNT(*) FROM widget", Integer.class);

        assertThat(dataSource.handedOut.get())
                .as("three statements, three round trips to the pool")
                .isEqualTo(3);
    }

    @Test
    void insideATransactionEveryCallSharesOneConnection() {
        transactions.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO widget (id, name) VALUES (?, ?)", "w1", "one");
            jdbc.update("INSERT INTO widget (id, name) VALUES (?, ?)", "w2", "two");
            jdbc.queryForObject("SELECT COUNT(*) FROM widget", Integer.class);
        });

        assertThat(dataSource.handedOut.get())
                .as("one connection, bound to the thread for the life of the transaction")
                .isEqualTo(1);
    }

    @Test
    void theConnectionIsBoundToTheThreadNotToTheTemplate() {
        transactions.executeWithoutResult(status -> {
            assertThat(TransactionSynchronizationManager.hasResource(dataSource)).isTrue();

            Connection first = DataSourceUtils.getConnection(dataSource);
            Connection second = DataSourceUtils.getConnection(dataSource);
            assertThat(first).isSameAs(second);
        });

        assertThat(TransactionSynchronizationManager.hasResource(dataSource))
                .as("unbound again once the transaction completes")
                .isFalse();
    }

    @Test
    void aRollbackUndoesEverythingOnThatSharedConnection() {
        transactions.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO widget (id, name) VALUES (?, ?)", "w1", "one");
            status.setRollbackOnly();
        });

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM widget", Integer.class)).isZero();
    }

    /** Counts how many times something asked the pool for a connection. */
    static class CountingDataSource extends DelegatingDataSource {

        final AtomicInteger handedOut = new AtomicInteger();

        CountingDataSource(DataSource target) {
            super(target);
        }

        @Override
        public Connection getConnection() throws SQLException {
            handedOut.incrementAndGet();
            return super.getConnection();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Config {

        @Bean
        CountingDataSource dataSource() {
            return new CountingDataSource(new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .generateUniqueName(true)
                    .build());
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        TransactionTemplate transactionTemplate(PlatformTransactionManager manager) {
            return new TransactionTemplate(manager);
        }
    }
}
