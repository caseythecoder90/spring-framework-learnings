package com.caseythecoder.spring.transactions;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The smallest thing that can tell you whether a transaction committed: rows that are there, or
 * rows that are not.
 *
 * <p>Deliberately not {@code @Transactional}. Every transaction in this lab is started by the bean
 * calling into this one, so that the propagation under test is the only propagation in play.
 */
class Ledger {

    private final JdbcTemplate jdbc;

    Ledger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void insert(String id) {
        jdbc.update("insert into item(id, label) values (?, ?)", id, id);
    }

    /** Ids currently visible from a fresh connection, sorted, so assertions read as sets. */
    List<String> ids() {
        return jdbc.queryForList("select id from item order by id", String.class);
    }

    void clear() {
        jdbc.update("delete from item");
    }
}
