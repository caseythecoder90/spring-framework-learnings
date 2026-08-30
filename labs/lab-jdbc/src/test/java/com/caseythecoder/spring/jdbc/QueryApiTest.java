package com.caseythecoder.spring.jdbc;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three generations of query API, all still present and all still used.
 *
 * <p>{@code JdbcTemplate} with positional parameters, {@code NamedParameterJdbcTemplate} with named
 * ones and list expansion, and {@code JdbcClient} (Framework 6.1) which is a fluent facade over
 * both and the one to reach for in new code.
 *
 * <p>Notes: docs/jdbctemplate.md, "Three APIs".
 */
@SpringJUnitConfig(QueryApiTest.Config.class)
class QueryApiTest {

    record Widget(String id, String name) {
    }

    private static final RowMapper<Widget> WIDGET =
            (rs, rowNum) -> new Widget(rs.getString("id"), rs.getString("name"));

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    NamedParameterJdbcTemplate named;

    @Autowired
    JdbcClient client;

    @BeforeEach
    void reset() {
        jdbc.execute("DROP TABLE IF EXISTS widget");
        jdbc.execute("CREATE TABLE widget (id VARCHAR(16) PRIMARY KEY, name VARCHAR(64))");
        jdbc.batchUpdate("INSERT INTO widget (id, name) VALUES (?, ?)",
                List.of(new Object[] { "w1", "one" }, new Object[] { "w2", "two" }, new Object[] { "w3", "three" }));
    }

    @Test
    void batchUpdateReportsARowCountPerStatement() {
        int[] counts = jdbc.batchUpdate("UPDATE widget SET name = ? WHERE id = ?",
                List.of(new Object[] { "ONE", "w1" }, new Object[] { "TWO", "w2" }));

        assertThat(counts).containsExactly(1, 1);
    }

    @Test
    void aRowMapperTurnsEachRowIntoAnObject() {
        List<Widget> widgets = jdbc.query("SELECT id, name FROM widget ORDER BY id", WIDGET);

        assertThat(widgets).extracting(Widget::name).containsExactly("one", "two", "three");
    }

    @Test
    void namedParametersExpandAListForAnInClause() {
        // The reason NamedParameterJdbcTemplate exists. A positional ? cannot take a list, so
        // building an IN clause by hand is where SQL injection usually creeps in.
        List<Widget> widgets = named.query(
                "SELECT id, name FROM widget WHERE id IN (:ids) ORDER BY id",
                Map.of("ids", List.of("w1", "w3")),
                WIDGET);

        assertThat(widgets).extracting(Widget::id).containsExactly("w1", "w3");
    }

    @Test
    void jdbcClientIsTheFluentFacadeOverBoth() {
        List<Widget> widgets = client.sql("SELECT id, name FROM widget WHERE id = :id")
                .param("id", "w2")
                .query(WIDGET)
                .list();

        assertThat(widgets).singleElement().extracting(Widget::name).isEqualTo("two");
    }

    @Test
    void jdbcClientGivesYouOptionalInsteadOfAnException() {
        // The fix for queryForObject's zero-rows behaviour: optional() rather than a throw.
        Optional<Widget> missing = client.sql("SELECT id, name FROM widget WHERE id = :id")
                .param("id", "nope")
                .query(WIDGET)
                .optional();

        assertThat(missing).isEmpty();
    }

    @Test
    void jdbcClientCanMapToARecordWithoutARowMapper() {
        List<Widget> widgets = client.sql("SELECT id, name FROM widget ORDER BY id")
                .query(Widget.class)
                .list();

        assertThat(widgets).extracting(Widget::id).containsExactly("w1", "w2", "w3");
    }

    @Test
    void jdbcClientAlsoTakesPositionalParameters() {
        // Either style, chosen by which param() overload you call. One statement uses one style;
        // the indexes here are 1-based, as in JDBC itself.
        int updated = client.sql("UPDATE widget SET name = ? WHERE id = ?")
                .param(1, "renamed")
                .param(2, "w1")
                .update();

        assertThat(updated).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT name FROM widget WHERE id = 'w1'", String.class))
                .isEqualTo("renamed");
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

        @Bean
        NamedParameterJdbcTemplate namedParameterJdbcTemplate(JdbcTemplate jdbcTemplate) {
            return new NamedParameterJdbcTemplate(jdbcTemplate);
        }

        @Bean
        JdbcClient jdbcClient(JdbcTemplate jdbcTemplate) {
            return JdbcClient.create(jdbcTemplate);
        }
    }
}
