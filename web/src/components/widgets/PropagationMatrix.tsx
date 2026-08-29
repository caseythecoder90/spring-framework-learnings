import { useState } from 'react';
import ToggleGroup from '../ui/ToggleGroup';

type Mode = 'REQUIRED' | 'REQUIRES_NEW' | 'NESTED' | 'SUPPORTS' | 'MANDATORY' | 'NEVER' | 'NOT_SUPPORTED';
type Caller = 'in-transaction' | 'none';
type Inner = 'commits' | 'caught' | 'propagates';

interface Result {
  /** What AbstractPlatformTransactionManager does with the transaction that is already running. */
  manager: string;
  /** Does the caller's own write survive? null when there is no caller transaction. */
  outer: string | null;
  outerLost: boolean;
  /** Does the inner write survive? */
  inner: string;
  innerLost: boolean;
  /** What comes back out of the outer call. */
  sees: string;
  why: string;
  bad: boolean;
}

/**
 * Mirrors AbstractPlatformTransactionManager.getTransaction() and handleExistingTransaction(),
 * scored by what is left in the table afterwards rather than by vocabulary.
 *
 * Every combination here is pinned by a test in labs/lab-transactions/PropagationTest.
 */
function resolve(mode: Mode, caller: Caller, inner: Inner): Result {
  const outerThrew = inner === 'propagates';

  if (caller === 'none') {
    if (mode === 'MANDATORY') {
      return {
        manager: 'throws IllegalTransactionStateException before your method runs',
        outer: null,
        outerLost: false,
        inner: 'never ran',
        innerLost: true,
        sees: 'IllegalTransactionStateException',
        why: 'MANDATORY is an assertion, not a transaction. It fails loudly, which is the point of it.',
        bad: false,
      };
    }
    if (mode === 'NEVER' || mode === 'NOT_SUPPORTED' || mode === 'SUPPORTS') {
      return {
        manager: 'runs with no transaction at all',
        outer: null,
        outerLost: false,
        inner: outerThrew ? 'committed on autocommit, before the throw' : 'committed on autocommit',
        innerLost: false,
        sees: outerThrew ? 'your exception' : 'a normal return',
        why:
          mode === 'SUPPORTS'
            ? 'The SUPPORTS trap: the method reads as transactional and every statement is its own transaction. Two writes here are two commits, and a failure between them leaves half the work done.'
            : 'No transaction means autocommit, so each statement commits itself. Nothing can be rolled back later.',
        bad: true,
      };
    }
    return {
      manager: 'starts a new transaction (nothing to join, so NESTED and REQUIRES_NEW behave like REQUIRED)',
      outer: null,
      outerLost: false,
      inner: outerThrew ? 'rolled back' : 'committed',
      innerLost: outerThrew,
      sees: outerThrew ? 'your exception' : 'a normal return',
      why: 'Every propagation mode that can start a transaction does exactly the same thing when there is none to join.',
      bad: false,
    };
  }

  switch (mode) {
    case 'REQUIRED':
    case 'SUPPORTS': {
      const joined = 'joins the caller’s transaction — one physical transaction, one commit';
      if (inner === 'commits') {
        return {
          manager: joined,
          outer: 'committed',
          outerLost: false,
          inner: 'committed',
          innerLost: false,
          sees: 'a normal return',
          why: 'The ordinary case, and the reason REQUIRED is the default.',
          bad: false,
        };
      }
      if (inner === 'caught') {
        return {
          manager: joined,
          outer: 'rolled back',
          outerLost: true,
          inner: 'rolled back',
          innerLost: true,
          sees: 'UnexpectedRollbackException',
          why: 'The inner interceptor set rollbackOnly on the shared transaction on its way out. Catching the exception changed nothing: the commit at the end of the outer method turns into a rollback, and the caller gets an exception it has never heard of. This is the single most surprising thing about REQUIRED.',
          bad: true,
        };
      }
      return {
        manager: joined,
        outer: 'rolled back',
        outerLost: true,
        inner: 'rolled back',
        innerLost: true,
        sees: 'your exception',
        why: 'One transaction, one rollback. Exactly what you would expect.',
        bad: false,
      };
    }

    case 'REQUIRES_NEW': {
      const suspended = 'suspends the caller and begins a second transaction on a second connection';
      if (inner === 'commits') {
        return {
          manager: suspended,
          outer: outerThrew ? 'rolled back' : 'committed',
          outerLost: false,
          inner: 'committed, independently and immediately',
          innerLost: false,
          sees: 'a normal return',
          why: 'Two transactions. The inner one is already durable while the outer one is still open, so an outer rollback cannot take it back.',
          bad: false,
        };
      }
      if (inner === 'caught') {
        return {
          manager: suspended,
          outer: 'committed',
          outerLost: false,
          inner: 'rolled back',
          innerLost: true,
          sees: 'a normal return',
          why: 'A suspended transaction has its own rollback flag, so the caller really can recover. This is the fix for the REQUIRED case above.',
          bad: false,
        };
      }
      return {
        manager: suspended,
        outer: 'rolled back',
        outerLost: true,
        inner: 'rolled back',
        innerLost: true,
        sees: 'your exception',
        why: 'Both roll back, but for unrelated reasons and at different times.',
        bad: false,
      };
    }

    case 'NESTED': {
      const savepoint = 'takes a JDBC savepoint — still one physical transaction';
      if (inner === 'commits') {
        return {
          manager: savepoint,
          outer: outerThrew ? 'rolled back' : 'committed',
          outerLost: outerThrew,
          inner: outerThrew ? 'rolled back' : 'committed',
          innerLost: outerThrew,
          sees: outerThrew ? 'your exception' : 'a normal return',
          why: 'Releasing a savepoint commits nothing on its own. NESTED work becomes durable only when the outer transaction commits, which is exactly how it differs from REQUIRES_NEW.',
          bad: false,
        };
      }
      if (inner === 'caught') {
        return {
          manager: savepoint,
          outer: 'committed',
          outerLost: false,
          inner: 'rolled back to the savepoint',
          innerLost: true,
          sees: 'a normal return',
          why: 'The only mode where catching the exception genuinely works without a second connection. Needs a transaction manager and driver that support savepoints; JPA and JTA generally do not.',
          bad: false,
        };
      }
      return {
        manager: savepoint,
        outer: 'rolled back',
        outerLost: true,
        inner: 'rolled back',
        innerLost: true,
        sees: 'your exception',
        why: 'One transaction underneath, so an outer failure takes the savepointed work with it.',
        bad: false,
      };
    }

    case 'MANDATORY':
      return {
        manager: 'joins, happily — the assertion passed',
        outer: inner === 'commits' ? 'committed' : 'rolled back',
        outerLost: inner !== 'commits',
        inner: inner === 'commits' ? 'committed' : 'rolled back',
        innerLost: inner !== 'commits',
        sees:
          inner === 'caught'
            ? 'UnexpectedRollbackException'
            : inner === 'commits'
              ? 'a normal return'
              : 'your exception',
        why: 'MANDATORY is REQUIRED plus a precondition. Once the precondition holds it behaves identically, rollback-only poisoning included.',
        bad: inner === 'caught',
      };

    case 'NEVER':
      return {
        manager: 'throws IllegalTransactionStateException before your method runs',
        outer: inner === 'caught' ? 'committed' : 'rolled back',
        outerLost: inner !== 'caught',
        inner: 'never ran',
        innerLost: true,
        sees: inner === 'caught' ? 'a normal return' : 'IllegalTransactionStateException',
        why: 'NEVER is the mirror image of MANDATORY: an assertion that there is no transaction. Useful on a long-running method you do not want holding a connection.',
        bad: false,
      };

    case 'NOT_SUPPORTED':
      return {
        manager: 'suspends the caller and runs with no transaction',
        outer: outerThrew ? 'rolled back' : 'committed',
        outerLost: outerThrew,
        inner: 'committed on autocommit, and out of your hands',
        innerLost: false,
        sees: inner === 'commits' ? 'a normal return' : inner === 'caught' ? 'a normal return' : 'your exception',
        why: 'Suspended means autocommit on a second connection. Every statement commits itself the moment it runs, so nothing that happens afterwards — an outer rollback, a thrown exception, a crash — can undo it.',
        bad: outerThrew,
      };
  }
}

const MODES: { label: string; value: Mode }[] = [
  { label: 'REQUIRED', value: 'REQUIRED' },
  { label: 'REQUIRES_NEW', value: 'REQUIRES_NEW' },
  { label: 'NESTED', value: 'NESTED' },
  { label: 'SUPPORTS', value: 'SUPPORTS' },
  { label: 'MANDATORY', value: 'MANDATORY' },
  { label: 'NEVER', value: 'NEVER' },
  { label: 'NOT_SUPPORTED', value: 'NOT_SUPPORTED' },
];

export default function PropagationMatrix() {
  const [mode, setMode] = useState<Mode>('REQUIRED');
  const [caller, setCaller] = useState<Caller>('in-transaction');
  const [inner, setInner] = useState<Inner>('caught');

  const effectiveInner: Inner = caller === 'none' && inner === 'caught' ? 'commits' : inner;
  const result = resolve(mode, caller, effectiveInner);

  const rows: { label: string; text: string; lost: boolean }[] = [
    { label: 'the transaction manager', text: result.manager, lost: false },
    ...(result.outer === null
      ? []
      : [{ label: 'the caller’s write', text: result.outer, lost: result.outerLost }]),
    { label: 'the inner write', text: result.inner, lost: result.innerLost },
    { label: 'what the caller sees', text: result.sees, lost: result.bad },
  ];

  return (
    <div className="w">
      <div className="widget-head">
        <span className="widget-title">What survives?</span>
        <span className="widget-sub">AbstractPlatformTransactionManager.handleExistingTransaction()</span>
      </div>

      <div className="ctl-row">
        <ToggleGroup label="caller" options={[
          { label: '@Transactional', value: 'in-transaction' as const },
          { label: 'no transaction', value: 'none' as const },
        ]} value={caller} onChange={setCaller} />
        <ToggleGroup label="inner method" options={[
          { label: 'commits', value: 'commits' as const },
          { label: 'throws, caller catches', value: 'caught' as const },
          { label: 'throws, propagates', value: 'propagates' as const },
        ]} value={effectiveInner} onChange={setInner} />
      </div>

      <div className="ctl-row">
        <ToggleGroup label="inner @Transactional(propagation = ...)" options={MODES} value={mode} onChange={setMode} />
      </div>

      <ul className="steps">
        {rows.map((row) => (
          <li key={row.label} className={row.lost ? 'taken bad' : 'taken'}>
            <span className="n" style={{ minWidth: '10.5rem', textAlign: 'right' }}>{row.label}</span>
            <span className="txt">{row.text}</span>
          </li>
        ))}
      </ul>

      <div className={result.bad ? 'outcome bad' : 'outcome'}>
        <span className="lbl">why</span>
        <span className="why">{result.why}</span>
      </div>
    </div>
  );
}
