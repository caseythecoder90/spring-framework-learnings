package com.caseythecoder.spring.jdbc;

import java.sql.SQLException;
import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator;
import org.springframework.jdbc.support.SQLExceptionTranslator;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The reason nothing in Spring's data access API throws {@code SQLException}: every driver
 * exception is translated into an unchecked {@code DataAccessException} subclass, so the same
 * "duplicate key" means the same thing whichever database you are on.
 *
 * <p>Which translator does that changed in Framework 6. The default is now
 * {@code SQLExceptionSubclassTranslator}, which uses the JDBC 4 {@code SQLException} subclasses and
 * SQL state, rather than the vendor error-code table most people remember.
 *
 * <p>Notes: docs/jdbctemplate.md, "Exception translation".
 */
@SpringJUnitConfig(ExceptionTranslationTest.Config.class)
class ExceptionTranslationTest {

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.execute("DROP TABLE IF EXISTS widget");
        jdbc.execute("CREATE TABLE widget (id VARCHAR(16) PRIMARY KEY, name VARCHAR(64))");
        jdbc.update("INSERT INTO widget (id, name) VALUES (?, ?)", "w1", "one");
    }

    @Test
    void theDefaultTranslatorIsTheSubclassOneSinceFramework6() {
        SQLExceptionTranslator translator = jdbc.getExceptionTranslator();

        assertThat(translator)
                .as("SQLErrorCodeSQLExceptionTranslator is only used when you supply an error-codes file")
                .isInstanceOf(SQLExceptionSubclassTranslator.class);
    }

    @Test
    void aDuplicateKeyBecomesDuplicateKeyException() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO widget (id, name) VALUES (?, ?)", "w1", "again"))
                .isInstanceOf(DuplicateKeyException.class)
                .isInstanceOf(DataAccessException.class)
                .hasCauseInstanceOf(SQLException.class);
    }

    @Test
    void brokenSqlBecomesBadSqlGrammarException() {
        assertThatThrownBy(() -> jdbc.queryForObject("SELECT nope FROM widget", String.class))
                .isInstanceOf(BadSqlGrammarException.class)
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void everyTranslatedExceptionIsUnchecked() {
        // DataAccessException extends NestedRuntimeException, which is why no Spring data access
        // method declares throws. The driver's SQLException is always kept as the cause.
        assertThat(DataAccessException.class).isAssignableTo(RuntimeException.class);
    }

    @Test
    void queryForObjectThrowsRatherThanReturningNullWhenThereAreNoRows() {
        // The single most surprising method in the API. Zero rows is an exception, not an empty
        // Optional and not a null.
        assertThatThrownBy(() -> jdbc.queryForObject(
                "SELECT name FROM widget WHERE id = ?", String.class, "missing"))
                .isInstanceOf(EmptyResultDataAccessException.class);
    }

    @Test
    void queryForObjectAlsoThrowsWhenThereIsMoreThanOneRow() {
        jdbc.update("INSERT INTO widget (id, name) VALUES (?, ?)", "w2", "two");

        assertThatThrownBy(() -> jdbc.queryForObject("SELECT name FROM widget", String.class))
                .isInstanceOf(IncorrectResultSizeDataAccessException.class);
    }

    @Test
    void queryForListIsTheSafeWayToAskForZeroOrOne() {
        assertThat(jdbc.queryForList("SELECT name FROM widget WHERE id = ?", String.class, "missing"))
                .isEmpty();
    }

    @Configuration(proxyBeanMethods = false)
    static class Config {

        @Bean
        DataSource dataSource() {
            return new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .generateUniqueName(true)
                    .build();
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }
    }
}
