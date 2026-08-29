package com.caseythecoder.spring.transactions;

import java.io.IOException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which exceptions roll back, which ones quietly commit, and how the rules are resolved when more
 * than one matches.
 *
 * <p>The default comes from {@code DefaultTransactionAttribute.rollbackOn}, which is one line:
 * {@code RuntimeException} or {@code Error}. A checked exception commits. That is not a bug and it
 * is not EJB nostalgia either - it is the documented default, and it is still the thing most
 * likely to lose somebody's data.
 *
 * <p>Notes: docs/transactions.md, "Rollback rules".
 */
@SpringJUnitConfig(RollbackRulesTest.Config.class)
class RollbackRulesTest {

    @Autowired
    Service service;

    @Autowired
    Ledger ledger;

    @BeforeEach
    void reset() {
        ledger.clear();
    }

    @Test
    void aCheckedExceptionCommitsTheWorkThatCameBeforeIt() {
        assertThatThrownBy(() -> service.writeThenThrowChecked())
                .isInstanceOf(IOException.class);

        assertThat(ledger.ids())
                .as("the default rollback rule does not cover checked exceptions")
                .containsExactly("written");
    }

    @Test
    void aRuntimeExceptionRollsBack() {
        assertThatThrownBy(() -> service.writeThenThrowRuntime())
                .isInstanceOf(IllegalStateException.class);

        assertThat(ledger.ids()).isEmpty();
    }

    @Test
    void anErrorRollsBackToo() {
        assertThatThrownBy(() -> service.writeThenThrowError())
                .isInstanceOf(AssertionError.class);

        assertThat(ledger.ids()).isEmpty();
    }

    @Test
    void rollbackForBringsCheckedExceptionsUnderTheRule() {
        assertThatThrownBy(() -> service.writeThenThrowCheckedWithRollbackFor())
                .isInstanceOf(IOException.class);

        assertThat(ledger.ids()).isEmpty();
    }

    @Test
    void noRollbackForLetsARuntimeExceptionCommit() {
        assertThatThrownBy(() -> service.writeThenThrowRuntimeWithNoRollbackFor())
                .isInstanceOf(IllegalStateException.class);

        assertThat(ledger.ids()).containsExactly("written");
    }

    @Test
    void theRuleClosestToTheThrownTypeWinsNotTheOrderTheyAreWrittenIn() {
        // rollbackFor = Exception, noRollbackFor = IllegalStateException. Both match, and the
        // winner is the one fewer superclasses away from what was thrown: RuleBasedTransactionAttribute
        // scores each rule by depth in the hierarchy, so the narrower rule wins regardless of order.
        assertThatThrownBy(() -> service.writeThenThrowWithBothRules(new IllegalStateException("narrow")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(ledger.ids()).as("noRollbackFor matched at depth 0").containsExactly("written");

        ledger.clear();

        assertThatThrownBy(() -> service.writeThenThrowWithBothRules(new IOException("wide")))
                .isInstanceOf(IOException.class);
        assertThat(ledger.ids()).as("only the wide rollbackFor rule matched").isEmpty();
    }

    @Test
    void rollbackOnlySetFromInsideRollsBackWithoutAnExceptionEverEscaping() {
        // The alternative to throwing. Nothing propagates to the caller, and nothing is committed.
        service.writeThenMarkRollbackOnly();

        assertThat(ledger.ids()).isEmpty();
    }

    @Test
    void anExceptionCaughtInsideTheMethodIsNotAnExceptionAtAll() {
        // Rollback is driven by what leaves the method through the proxy. Handle it internally and
        // the interceptor never sees it, which is exactly what you want - and exactly what people
        // forget when they wrap a whole service method in try/catch and log.
        service.writeThenCatchItsOwnFailure();

        assertThat(ledger.ids()).containsExactly("written");
    }

    // ---------------------------------------------------------------------------------------

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(DatabaseConfig.class)
    static class Config {

        @Bean
        Service service(Ledger ledger) {
            return new Service(ledger);
        }
    }

    static class Service {

        private final Ledger ledger;

        Service(Ledger ledger) {
            this.ledger = ledger;
        }

        @Transactional
        public void writeThenThrowChecked() throws IOException {
            ledger.insert("written");
            throw new IOException("checked");
        }

        @Transactional
        public void writeThenThrowRuntime() {
            ledger.insert("written");
            throw new IllegalStateException("unchecked");
        }

        @Transactional
        public void writeThenThrowError() {
            ledger.insert("written");
            throw new AssertionError("error");
        }

        @Transactional(rollbackFor = IOException.class)
        public void writeThenThrowCheckedWithRollbackFor() throws IOException {
            ledger.insert("written");
            throw new IOException("checked");
        }

        @Transactional(noRollbackFor = IllegalStateException.class)
        public void writeThenThrowRuntimeWithNoRollbackFor() {
            ledger.insert("written");
            throw new IllegalStateException("unchecked");
        }

        @Transactional(rollbackFor = Exception.class, noRollbackFor = IllegalStateException.class)
        public void writeThenThrowWithBothRules(Exception toThrow) throws Exception {
            ledger.insert("written");
            throw toThrow;
        }

        @Transactional
        public void writeThenMarkRollbackOnly() {
            ledger.insert("written");
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        }

        @Transactional
        public void writeThenCatchItsOwnFailure() {
            ledger.insert("written");
            try {
                throw new IllegalStateException("handled here");
            }
            catch (IllegalStateException handled) {
                // Never reaches the interceptor, so there is nothing to roll back on.
            }
        }
    }
}
