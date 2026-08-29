package com.caseythecoder.spring.transactions;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * A real embedded database and a real {@link JdbcTransactionManager}, shared by every test class in
 * this lab.
 *
 * <p>Using the real thing matters here. {@code REQUIRES_NEW} genuinely takes a second connection
 * out of the pool, {@code NESTED} genuinely issues a JDBC savepoint, and a mock transaction manager
 * would let a wrong note pass.
 */
@Configuration(proxyBeanMethods = false)
class DatabaseConfig {

    @Bean
    DataSource dataSource() {
        return new EmbeddedDatabaseBuilder()
                // Each test class gets its own database, so a class that leaves rows behind cannot
                // change what another class sees.
                .generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2)
                .addScript("classpath:schema.sql")
                .build();
    }

    @Bean
    JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /**
     * {@code JdbcTransactionManager} is {@code DataSourceTransactionManager} plus exception
     * translation, and has been the recommended one since Framework 5.3.
     */
    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new JdbcTransactionManager(dataSource);
    }

    @Bean
    Ledger ledger(JdbcTemplate jdbcTemplate) {
        return new Ledger(jdbcTemplate);
    }
}
