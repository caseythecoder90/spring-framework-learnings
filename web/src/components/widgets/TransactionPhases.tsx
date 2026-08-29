import { useEffect, useMemo, useState } from 'react';
import ToggleGroup from '../ui/ToggleGroup';

type Mode = 'commit' | 'rollback' | 'none';

interface Listener {
  name: string;
  /** Position on the timeline (0..1), or false when this listener does not run at all. */
  at: (mode: Mode) => number | false;
}

const LISTENERS: Listener[] = [
  { name: '@EventListener', at: () => 0.34 },
  { name: 'BEFORE_COMMIT', at: (m) => (m === 'commit' ? 0.66 : false) },
  { name: 'AFTER_COMMIT (default)', at: (m) => (m === 'commit' ? 0.82 : false) },
  { name: 'AFTER_ROLLBACK', at: (m) => (m === 'rollback' ? 0.82 : false) },
  { name: 'AFTER_COMPLETION', at: (m) => (m === 'none' ? false : 0.9) },
  // fallbackExecution only changes the no-transaction case; a rollback still skips AFTER_COMMIT.
  { name: 'AFTER_COMMIT, fallbackExecution', at: (m) => (m === 'commit' ? 0.82 : m === 'none' ? 0.34 : false) },
];

const MARKS: Record<Mode, { at: number; text: string }[]> = {
  commit: [
    { at: 0.12, text: 'method begins' },
    { at: 0.34, text: 'publishEvent' },
    { at: 0.58, text: 'method returns' },
    { at: 0.74, text: 'commit' },
  ],
  rollback: [
    { at: 0.12, text: 'method begins' },
    { at: 0.34, text: 'publishEvent' },
    { at: 0.58, text: 'throws' },
    { at: 0.74, text: 'rollback' },
  ],
  none: [{ at: 0.34, text: 'publishEvent' }],
};

const EXPLANATION: Record<Mode, string> = {
  commit:
    'Everything after publishEvent waits for the commit, which happens after your method has already returned.',
  rollback: 'The AFTER_COMMIT listener never runs. That is usually exactly what you wanted.',
  none:
    'No transaction to register a synchronization on, so every @TransactionalEventListener is skipped. The only trace is a DEBUG log: "No transaction is active - skipping". Only fallbackExecution = true still runs.',
};

export default function TransactionPhases() {
  const [mode, setMode] = useState<Mode>('commit');
  const [run, setRun] = useState(0);
  const [firedCount, setFiredCount] = useState(0);

  const firing = useMemo(() => {
    return LISTENERS.map((l) => ({ name: l.name, at: l.at(mode) }))
      .filter((l): l is { name: string; at: number } => l.at !== false)
      .sort((a, b) => a.at - b.at);
  }, [mode]);

  useEffect(() => {
    setFiredCount(0);
    const timers = firing.map((_, i) =>
      window.setTimeout(() => setFiredCount((n) => Math.max(n, i + 1)), 220 + i * 320)
    );
    return () => timers.forEach(window.clearTimeout);
  }, [firing, run]);

  const firedAt = new Set(firing.slice(0, firedCount).map((f) => f.name));

  return (
    <div className="w">
      <div className="widget-head">
        <span className="widget-title">Which listeners actually fire?</span>
        <span className="widget-sub">TransactionalApplicationListenerMethodAdapter</span>
      </div>

      <div className="ctl-row">
        <ToggleGroup
          label="transaction outcome"
          options={[
            { label: 'commit', value: 'commit' as const },
            { label: 'rollback', value: 'rollback' as const },
            { label: 'no transaction', value: 'none' as const },
          ]}
          value={mode}
          onChange={setMode}
        />
        <div className="ctl">
          <div className="ctl-label">&nbsp;</div>
          <button type="button" className="ghost" onClick={() => setRun((n) => n + 1)}>
            Replay
          </button>
        </div>
      </div>

      <div className="txbar">
        {mode !== 'none' && (
          <div
            className={mode === 'rollback' ? 'span rolled' : 'span'}
            style={{ left: '12%', width: '62%' }}
          />
        )}
      </div>

      <div className="lanes">
        {LISTENERS.map((l) => {
          const at = l.at(mode);
          const skipped = at === false;
          return (
            <div key={l.name} className={skipped ? 'lane skipped' : 'lane'}>
              <div className="lane-name">{l.name}</div>
              <div className="lane-track">
                {skipped ? (
                  <div className="lane-tag">{mode === 'none' ? 'silently skipped' : 'wrong phase'}</div>
                ) : (
                  <div
                    className={firedAt.has(l.name) ? 'lane-dot fired' : 'lane-dot'}
                    style={{ left: `${at * 100}%` }}
                  />
                )}
              </div>
            </div>
          );
        })}
      </div>

      <div className="axis">
        {MARKS[mode].map((m) => (
          <span key={m.text}>
            <span className="tick" style={{ left: `${m.at * 100}%` }} />
            <span className="mark" style={{ left: `${m.at * 100}%` }}>
              {m.text}
            </span>
          </span>
        ))}
      </div>

      <div className={mode === 'none' ? 'outcome bad' : 'outcome'}>
        <span className="lbl">what happens</span>
        <span className="why">{EXPLANATION[mode]}</span>
      </div>
    </div>
  );
}
