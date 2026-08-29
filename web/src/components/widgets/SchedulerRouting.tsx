import { useState } from 'react';
import ToggleGroup from '../ui/ToggleGroup';

const STEPS = [
  'Qualifier on @Scheduled(scheduler = "...")',
  'Unique TaskScheduler bean, by type',
  'Several TaskSchedulers: the one named taskScheduler',
  'No TaskScheduler at all: unique ScheduledExecutorService',
  'Several of those: the one named taskScheduler',
  'Executors.newSingleThreadScheduledExecutor()',
] as const;

interface State {
  qualifier: boolean;
  taskSchedulers: 0 | 1 | 2;
  oneNamed: boolean;
  onePrimary: boolean;
  executors: 0 | 1 | 2;
  executorNamed: boolean;
}

interface Result {
  step: number;
  reached: number[];
  outcome: string;
  why: string;
  bad: boolean;
}

/**
 * Mirrors TaskSchedulerRouter.determineDefaultScheduler(). The branch worth noticing is that the
 * ScheduledExecutorService search only happens when there were zero TaskScheduler beans, which is
 * why steps 4 and 5 show as unreachable once you have two of them.
 */
function resolve(s: State): Result {
  if (s.qualifier) {
    return {
      step: 1,
      reached: [1],
      outcome: 'the bean named reportScheduler',
      why: 'The qualifier short-circuits the whole lookup.',
      bad: false,
    };
  }
  if (s.taskSchedulers === 1) {
    return {
      step: 2,
      reached: [1, 2],
      outcome: 'your single TaskScheduler bean',
      why: 'One candidate by type, so resolution succeeds immediately.',
      bad: false,
    };
  }
  if (s.taskSchedulers >= 2) {
    if (s.onePrimary) {
      return {
        step: 2,
        reached: [1, 2],
        outcome: 'the @Primary TaskScheduler bean',
        why: '@Primary makes by-type resolution unique again.',
        bad: false,
      };
    }
    if (s.oneNamed) {
      return {
        step: 3,
        reached: [1, 2, 3],
        outcome: 'the bean named taskScheduler',
        why: 'NoUniqueBeanDefinitionException, then a by-name lookup that hits.',
        bad: false,
      };
    }
    return {
      step: 6,
      reached: [1, 2, 3, 6],
      outcome: 'a private single-thread executor',
      why: 'Several TaskSchedulers and none named taskScheduler. The ScheduledExecutorService branch is never reached on this path; it only runs when there were zero TaskScheduler beans. All you get is one INFO line.',
      bad: true,
    };
  }
  if (s.executors === 1) {
    return {
      step: 4,
      reached: [1, 2, 4],
      outcome: 'your ScheduledExecutorService, wrapped in ConcurrentTaskScheduler',
      why: 'No TaskScheduler bean, so the search falls through to executors.',
      bad: false,
    };
  }
  if (s.executors >= 2) {
    if (s.executorNamed) {
      return {
        step: 5,
        reached: [1, 2, 4, 5],
        outcome: 'the ScheduledExecutorService named taskScheduler',
        why: 'Not unique by type, resolved by name instead.',
        bad: false,
      };
    }
    return {
      step: 6,
      reached: [1, 2, 4, 5, 6],
      outcome: 'a private single-thread executor',
      why: 'Several executors and none named taskScheduler.',
      bad: true,
    };
  }
  return {
    step: 6,
    reached: [1, 2, 4, 6],
    outcome: 'a private single-thread executor',
    why: 'Nothing to schedule on, so Spring quietly makes its own one-thread pool. This is the path Boot takes before TaskSchedulingAutoConfiguration contributes a scheduler.',
    bad: true,
  };
}

export default function SchedulerRouting() {
  const [state, setState] = useState<State>({
    qualifier: false,
    taskSchedulers: 1,
    oneNamed: false,
    onePrimary: false,
    executors: 0,
    executorNamed: false,
  });

  const set = <K extends keyof State>(key: K) => (value: State[K]) =>
    setState((prev) => ({ ...prev, [key]: value }));

  const result = resolve(state);
  const counts = [
    { label: '0', value: 0 as const },
    { label: '1', value: 1 as const },
    { label: '2+', value: 2 as const },
  ];
  const yesNo = [
    { label: 'no', value: false },
    { label: 'yes', value: true },
  ];

  return (
    <div className="w">
      <div className="widget-head">
        <span className="widget-title">Which scheduler runs your task?</span>
        <span className="widget-sub">TaskSchedulerRouter.determineDefaultScheduler()</span>
      </div>

      <div className="ctl-row">
        <ToggleGroup
          label="@Scheduled qualifier"
          options={[
            { label: 'none', value: false },
            { label: 'reportScheduler', value: true },
          ]}
          value={state.qualifier}
          onChange={set('qualifier')}
        />
        <ToggleGroup
          label="TaskScheduler beans"
          options={counts}
          value={state.taskSchedulers}
          onChange={set('taskSchedulers')}
          disabled={state.qualifier}
        />
        <ToggleGroup
          label="one named taskScheduler"
          options={yesNo}
          value={state.oneNamed}
          onChange={set('oneNamed')}
          disabled={state.qualifier || state.taskSchedulers < 2}
        />
        <ToggleGroup
          label="one is @Primary"
          options={yesNo}
          value={state.onePrimary}
          onChange={set('onePrimary')}
          disabled={state.qualifier || state.taskSchedulers < 2}
        />
        <ToggleGroup
          label="ScheduledExecutorService beans"
          options={counts}
          value={state.executors}
          onChange={set('executors')}
          disabled={state.qualifier || state.taskSchedulers !== 0}
        />
        <ToggleGroup
          label="one named taskScheduler"
          options={yesNo}
          value={state.executorNamed}
          onChange={set('executorNamed')}
          disabled={state.qualifier || state.taskSchedulers !== 0 || state.executors < 2}
        />
      </div>

      <ul className="steps">
        {STEPS.map((text, index) => {
          const n = index + 1;
          const reached = result.reached.includes(n);
          const className =
            n === result.step ? `taken${result.bad ? ' bad' : ''}` : reached ? '' : 'unreachable';
          return (
            <li key={text} className={className}>
              <span className="n">{n}</span>
              <span className="txt">{text}</span>
              {!reached && <span className="note">not reached</span>}
            </li>
          );
        })}
      </ul>

      <div className={result.bad ? 'outcome bad' : 'outcome'}>
        <span className="lbl">your task runs on</span>
        <span className="val">{result.outcome}</span>
        <span className="why">{result.why}</span>
      </div>
    </div>
  );
}
