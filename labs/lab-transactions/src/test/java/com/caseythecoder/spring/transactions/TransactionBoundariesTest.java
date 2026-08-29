package com.caseythecoder.spring.transactions;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;

import com.caseythecoder.spring.support.Recorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where a transaction begins and, more usefully, where it silently ends.
 *
 * <p>All four boundaries in here come from the same two facts: the transaction is started by a
 * proxy, and it lives in a {@link ThreadLocal}. Miss the proxy or leave the thread and there is no
 * transaction, with no error either way.
 *
 * <p>Notes: docs/transactions.md, "Where the transaction stops".
 */
@SpringJUnitConfig(TransactionBoundariesTest.Config.class)
class TransactionBoundariesTest {

    @Autowired
    Service service;

    @Autowired
    Ledger ledger;

    @Autowired
    DataSource dataSource;

    @BeforeEach
    void reset() {
        ledger.clear();
    }

    @Test
    void selfInvocationRunsWithNoTransactionAtAll() {
        assertThat(service.callsItsOwnTransactionalMethod())
                .as("the internal call never left the object, so it never met the proxy")
                .isFalse();
    }

    @Test
    void aNonPublicTransactionalMethodIsSilentlyIgnored() throws Exception {
        // AnnotationTransactionAttributeSource.publicMethodsOnly defaults to true, so a
        // non-public @Transactional method produces no attribute, no advice, and no warning.
        AnnotationTransactionAttributeSource source = new AnnotationTransactionAttributeSource();

        Method visible = Service.class.getDeclaredMethod("writeInTransaction");
        Method hidden = Service.class.getDeclaredMethod("writeInTransactionButPackagePrivate");

        assertThat(source.getTransactionAttribute(visible, Service.class)).isNotNull();
        assertThat(source.getTransactionAttribute(hidden, Service.class))
                .as("annotated, and completely inert")
                .isNull();
    }

    @Test
    void theTransactionDoesNotFollowYouOntoAnotherThread() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            assertThat(service.checksForATransactionOnAnotherThread(executor))
                    .as("TransactionSynchronizationManager is a ThreadLocal; a handed-off task starts empty")
                    .isFalse();
        }
        finally {
            executor.shutdownNow();
        }
    }

    @Test
    void everyCallInsideOneTransactionGetsTheSameConnection() {
        assertThat(service.takesTheConnectionTwice())
                .as("bound to the thread by TransactionSynchronizationManager, and handed back on request")
                .isTrue();
    }

    @Test
    void outsideATransactionEveryCallGetsAFreshConnection() {
        Connection first = DataSourceUtils.getConnection(dataSource);
        Connection second = DataSourceUtils.getConnection(dataSource);
        try {
            assertThat(first).isNotSameAs(second);
            assertThat(TransactionSynchronizationManager.getResource(dataSource)).isNull();
        }
        finally {
            DataSourceUtils.releaseConnection(second, dataSource);
            DataSourceUtils.releaseConnection(first, dataSource);
        }
    }

    @Test
    void synchronizationCallbacksAllRunAfterTheMethodHasReturned() {
        Recorder recorder = new Recorder();

        service.registersASynchronization(recorder);
        recorder.record("call returned");

        assertThat(recorder.labels()).containsExactly(
                "method body start",
                "method body end",
                "beforeCommit",
                "beforeCompletion",
                "afterCommit",
                "afterCompletion",
                // Everything above happened inside the proxy call. afterCommit is "after the
                // commit", not "after the caller got control back".
                "call returned");
    }

    @Test
    void afterCommitIsSkippedWhenTheTransactionRollsBack() {
        Recorder recorder = new Recorder();

        service.registersASynchronizationThenRollsBack(recorder);

        assertThat(recorder.labels())
                .as("beforeCommit is skipped too - it is a commit callback, not a completion one")
                .containsExactly("method body start", "method body end", "beforeCompletion", "afterCompletion");
    }

    @Test
    void readOnlyIsAHintThatDoesNotStopYouWriting() {
        // JdbcTransactionManager calls Connection.setReadOnly(true), which H2 accepts and ignores.
        // With a JPA transaction manager readOnly does much more (it sets the Hibernate flush mode
        // to MANUAL, so changes are simply never flushed). "readOnly means the write fails" is true
        // of neither: it means different things per transaction manager, and nothing at all here.
        service.writesInsideAReadOnlyTransaction();

        assertThat(ledger.ids()).containsExactly("written");
    }

    @Test
    void readOnlyIsVisibleToAnythingThatCaresToLook() {
        assertThat(service.reportsWhetherTheTransactionIsReadOnly()).isTrue();
        assertThat(service.reportsWhetherAWritableTransactionIsReadOnly()).isFalse();
    }

    // ---------------------------------------------------------------------------------------

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(DatabaseConfig.class)
    static class Config {

        @Bean
        Service service(Ledger ledger, DataSource dataSource) {
            return new Service(ledger, dataSource);
        }
    }

    static class Service {

        private final Ledger ledger;

        private final DataSource dataSource;

        Service(Ledger ledger, DataSource dataSource) {
            this.ledger = ledger;
            this.dataSource = dataSource;
        }

        public boolean callsItsOwnTransactionalMethod() {
            return reportsWhetherATransactionIsActive();
        }

        @Transactional
        public boolean reportsWhetherATransactionIsActive() {
            return TransactionSynchronizationManager.isActualTransactionActive();
        }

        @Transactional
        public void writeInTransaction() {
            ledger.insert("written");
        }

        @Transactional
        void writeInTransactionButPackagePrivate() {
            ledger.insert("written");
        }

        @Transactional
        public boolean checksForATransactionOnAnotherThread(ExecutorService executor) throws Exception {
            Future<Boolean> onOtherThread =
                    executor.submit(TransactionSynchronizationManager::isActualTransactionActive);
            return onOtherThread.get();
        }

        @Transactional
        public boolean takesTheConnectionTwice() {
            Connection first = DataSourceUtils.getConnection(dataSource);
            Connection second = DataSourceUtils.getConnection(dataSource);
            try {
                return first == second;
            }
            finally {
                DataSourceUtils.releaseConnection(second, dataSource);
                DataSourceUtils.releaseConnection(first, dataSource);
            }
        }

        @Transactional
        public void registersASynchronization(Recorder recorder) {
            recorder.record("method body start");
            TransactionSynchronizationManager.registerSynchronization(new RecordingSynchronization(recorder));
            recorder.record("method body end");
        }

        @Transactional
        public void registersASynchronizationThenRollsBack(Recorder recorder) {
            recorder.record("method body start");
            TransactionSynchronizationManager.registerSynchronization(new RecordingSynchronization(recorder));
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            recorder.record("method body end");
        }

        @Transactional(readOnly = true)
        public void writesInsideAReadOnlyTransaction() {
            ledger.insert("written");
        }

        @Transactional(readOnly = true)
        public boolean reportsWhetherTheTransactionIsReadOnly() {
            return TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        }

        @Transactional
        public boolean reportsWhetherAWritableTransactionIsReadOnly() {
            return TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        }
    }

    /** Records every callback the transaction manager fires, in the order it fires them. */
    private static final class RecordingSynchronization implements TransactionSynchronization {

        private final Recorder recorder;

        private RecordingSynchronization(Recorder recorder) {
            this.recorder = recorder;
        }

        @Override
        public void beforeCommit(boolean readOnly) {
            recorder.record("beforeCommit");
        }

        @Override
        public void beforeCompletion() {
            recorder.record("beforeCompletion");
        }

        @Override
        public void afterCommit() {
            recorder.record("afterCommit");
        }

        @Override
        public void afterCompletion(int status) {
            recorder.record("afterCompletion");
        }
    }
}
