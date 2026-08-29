package com.caseythecoder.spring.transactions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The seven propagation modes, each one proved by what survives in the database.
 *
 * <p>The whole matrix is one switch statement in
 * {@code AbstractPlatformTransactionManager.handleExistingTransaction}. These tests are that switch
 * statement, observed from outside.
 *
 * <p>Every test uses the same shape: an outer {@code @Transactional} method writes a row, calls an
 * inner bean, and then decides whether to throw. What is left in the table afterwards is the
 * answer.
 *
 * <p>Notes: docs/transactions.md, "The propagation matrix".
 */
@SpringJUnitConfig(PropagationTest.Config.class)
class PropagationTest {

    @Autowired
    Outer outer;

    @Autowired
    Inner inner;

    @Autowired
    Ledger ledger;

    @BeforeEach
    void reset() {
        ledger.clear();
    }

    @Test
    void requiredJoinsTheCallerSoBothWritesRollBackTogether() {
        assertThatThrownBy(() -> outer.writeThenCallRequiredThenFail())
                .isInstanceOf(IllegalStateException.class);

        assertThat(ledger.ids())
                .as("one transaction, one rollback, nothing survives")
                .isEmpty();
    }

    @Test
    void requiresNewSuspendsSoTheInnerWriteSurvivesTheOuterRollback() {
        assertThatThrownBy(() -> outer.writeThenCallRequiresNewThenFail())
                .isInstanceOf(IllegalStateException.class);

        assertThat(ledger.ids())
                .as("the inner transaction committed on its own connection before the outer one rolled back")
                .containsExactly("inner");
    }

    @Test
    void nestedRollsBackToASavepointAndLeavesTheOuterWorkIntact() {
        outer.writeThenCallNestedThatFails();

        assertThat(ledger.ids())
                .as("the savepoint took out the inner write only")
                .containsExactly("outer");
    }

    @Test
    void nestedCommitsWithTheOuterTransactionNotBeforeIt() {
        assertThatThrownBy(() -> outer.callNestedThenFail())
                .isInstanceOf(IllegalStateException.class);

        assertThat(ledger.ids())
                .as("a savepoint is not a transaction; releasing it commits nothing on its own")
                .isEmpty();
    }

    @Test
    void mandatoryThrowsWhenThereIsNoTransactionToJoin() {
        assertThatThrownBy(() -> inner.mandatory("x"))
                .isInstanceOf(IllegalTransactionStateException.class)
                .hasMessageContaining("'mandatory'");

        assertThat(ledger.ids()).isEmpty();
    }

    @Test
    void mandatoryIsHappyInsideOne() {
        outer.callMandatory();

        assertThat(ledger.ids()).containsExactly("inner");
    }

    @Test
    void neverThrowsWhenThereIsATransaction() {
        assertThatThrownBy(() -> outer.callNever())
                .isInstanceOf(IllegalTransactionStateException.class)
                .hasMessageContaining("'never'");
    }

    @Test
    void notSupportedSuspendsSoTheWriteIsCommittedImmediately() {
        assertThatThrownBy(() -> outer.writeThenCallNotSupportedThenFail())
                .isInstanceOf(IllegalStateException.class);

        assertThat(ledger.ids())
                .as("suspended means autocommit on a second connection, so the row is already gone from your control")
                .containsExactly("inner");
    }

    @Test
    void supportsRunsWithNoTransactionAtAllWhenThereIsNoneToJoin() {
        // The trap in SUPPORTS: the method reads as transactional and is not. It is only
        // transactional when somebody else already started one.
        assertThat(inner.supportsAndReportsWhetherATransactionIsActive()).isFalse();
        assertThat(outer.callSupports()).isTrue();
    }

    @Test
    void markingTheInnerTransactionRollbackOnlyPoisonsTheOuterCommit() {
        // The single most surprising thing about REQUIRED. The outer method catches the exception
        // and returns normally, so it believes it recovered - but the inner interceptor already
        // set rollbackOnly on the shared transaction, and the commit turns into a rollback.
        assertThatThrownBy(() -> outer.callRequiredThatFailsAndSwallowTheException())
                .isInstanceOf(UnexpectedRollbackException.class)
                .hasMessageContaining("marked as rollback-only");

        assertThat(ledger.ids()).as("including the write the outer method thought it kept").isEmpty();
    }

    @Test
    void requiresNewIsTheCureForThatBecauseItHasItsOwnRollbackFlag() {
        outer.callRequiresNewThatFailsAndSwallowTheException();

        assertThat(ledger.ids())
                .as("a suspended transaction has its own rollback flag, so the outer one is untouched")
                .containsExactly("outer");
    }

    // ---------------------------------------------------------------------------------------

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(DatabaseConfig.class)
    static class Config {

        @Bean
        Inner inner(Ledger ledger) {
            return new Inner(ledger);
        }

        @Bean
        Outer outer(Ledger ledger, Inner inner) {
            return new Outer(ledger, inner);
        }
    }

    /** Starts the outer transaction, then hands over to {@link Inner}. */
    static class Outer {

        private final Ledger ledger;

        private final Inner inner;

        Outer(Ledger ledger, Inner inner) {
            this.ledger = ledger;
            this.inner = inner;
        }

        @Transactional
        public void writeThenCallRequiredThenFail() {
            ledger.insert("outer");
            inner.required("inner");
            throw new IllegalStateException("outer fails");
        }

        @Transactional
        public void writeThenCallRequiresNewThenFail() {
            ledger.insert("outer");
            inner.requiresNew("inner");
            throw new IllegalStateException("outer fails");
        }

        @Transactional
        public void writeThenCallNotSupportedThenFail() {
            ledger.insert("outer");
            inner.notSupported("inner");
            throw new IllegalStateException("outer fails");
        }

        @Transactional
        public void writeThenCallNestedThatFails() {
            ledger.insert("outer");
            try {
                inner.nestedThenFail("inner");
            }
            catch (IllegalStateException expected) {
                // Rolling back to the savepoint does not mark the outer transaction rollback-only,
                // so unlike REQUIRED this catch really does recover.
            }
        }

        @Transactional
        public void callNestedThenFail() {
            inner.nested("inner");
            throw new IllegalStateException("outer fails");
        }

        @Transactional
        public void callMandatory() {
            inner.mandatory("inner");
        }

        @Transactional
        public void callNever() {
            inner.never("inner");
        }

        @Transactional
        public boolean callSupports() {
            return inner.supportsAndReportsWhetherATransactionIsActive();
        }

        @Transactional
        public void callRequiredThatFailsAndSwallowTheException() {
            ledger.insert("outer");
            try {
                inner.requiredThenFail("inner");
            }
            catch (IllegalStateException swallowed) {
                // "I handled it." You did not.
            }
        }

        @Transactional
        public void callRequiresNewThatFailsAndSwallowTheException() {
            ledger.insert("outer");
            try {
                inner.requiresNewThenFail("inner");
            }
            catch (IllegalStateException handled) {
                // This one really is handled.
            }
        }
    }

    /** One method per propagation mode, so each test names the mode it is about. */
    static class Inner {

        private final Ledger ledger;

        Inner(Ledger ledger) {
            this.ledger = ledger;
        }

        @Transactional(propagation = Propagation.REQUIRED)
        public void required(String id) {
            ledger.insert(id);
        }

        @Transactional(propagation = Propagation.REQUIRED)
        public void requiredThenFail(String id) {
            ledger.insert(id);
            throw new IllegalStateException("inner fails");
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void requiresNew(String id) {
            ledger.insert(id);
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void requiresNewThenFail(String id) {
            ledger.insert(id);
            throw new IllegalStateException("inner fails");
        }

        @Transactional(propagation = Propagation.NESTED)
        public void nested(String id) {
            ledger.insert(id);
        }

        @Transactional(propagation = Propagation.NESTED)
        public void nestedThenFail(String id) {
            ledger.insert(id);
            throw new IllegalStateException("inner fails");
        }

        @Transactional(propagation = Propagation.MANDATORY)
        public void mandatory(String id) {
            ledger.insert(id);
        }

        @Transactional(propagation = Propagation.NEVER)
        public void never(String id) {
            ledger.insert(id);
        }

        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        public void notSupported(String id) {
            ledger.insert(id);
        }

        @Transactional(propagation = Propagation.SUPPORTS)
        public boolean supportsAndReportsWhetherATransactionIsActive() {
            return TransactionSynchronizationManager.isActualTransactionActive();
        }
    }
}
