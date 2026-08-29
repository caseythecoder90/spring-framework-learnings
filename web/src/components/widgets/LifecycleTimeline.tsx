import { useState } from 'react';
import ToggleGroup from '../ui/ToggleGroup';

type Phase = 'instantiate' | 'populate' | 'initialise' | 'ready' | 'destroy';

interface Step {
  label: string;
  phase: Phase;
  /** Whether this callback fires, given what the bean declares. */
  when: (bean: BeanShape) => boolean;
  detail: string;
}

interface BeanShape {
  aware: boolean;
  postConstruct: boolean;
  initializingBean: boolean;
  customInit: boolean;
  advised: boolean;
  scope: 'singleton' | 'prototype';
}

const PHASES: Record<Phase, string> = {
  instantiate: 'createBeanInstance',
  populate: 'populateBean',
  initialise: 'initializeBean',
  ready: 'in use',
  destroy: 'DisposableBeanAdapter.destroy',
};

const STEPS: Step[] = [
  {
    label: 'constructor',
    phase: 'instantiate',
    when: () => true,
    detail: 'Constructor-injected dependencies are already resolved. Field-injected ones are not.',
  },
  {
    label: 'postProcessMergedBeanDefinition',
    phase: 'instantiate',
    when: () => true,
    detail: 'Where @Autowired metadata is collected, before anything is injected.',
  },
  {
    label: 'early reference published',
    phase: 'instantiate',
    when: (b) => b.scope === 'singleton',
    detail: 'addSingletonFactory exposes the half-built object. This is the only reason a field-injected cycle can work.',
  },
  {
    label: 'field and setter injection',
    phase: 'populate',
    when: () => true,
    detail: 'AutowiredAnnotationBeanPostProcessor.postProcessProperties. Injection is just a BeanPostProcessor.',
  },
  {
    label: 'Aware callbacks',
    phase: 'initialise',
    when: (b) => b.aware,
    detail: 'BeanName, BeanClassLoader and BeanFactory directly; the rest via ApplicationContextAwareProcessor.',
  },
  {
    label: '@PostConstruct',
    phase: 'initialise',
    when: (b) => b.postConstruct,
    detail: 'Dependencies are set, so this is the right place for work the constructor could not do. The bean is not proxied yet.',
  },
  {
    label: 'afterPropertiesSet',
    phase: 'initialise',
    when: (b) => b.initializingBean,
    detail: 'InitializingBean. Same timing as @PostConstruct but couples you to Spring, so it is rarely worth it.',
  },
  {
    label: 'custom init-method',
    phase: 'initialise',
    when: (b) => b.customInit,
    detail: 'Last of the three initialisation hooks.',
  },
  {
    label: 'proxy created',
    phase: 'initialise',
    when: (b) => b.advised,
    detail: 'AbstractAutoProxyCreator returns a different object from postProcessAfterInitialization. Everything above this line ran on the raw bean.',
  },
  {
    label: '@PreDestroy',
    phase: 'destroy',
    when: (b) => b.scope === 'singleton',
    detail: 'Prototypes never reach here. The container stops tracking them the moment it hands them over.',
  },
  {
    label: 'DisposableBean.destroy',
    phase: 'destroy',
    when: (b) => b.scope === 'singleton' && b.initializingBean,
    detail: 'Shutdown runs the three hooks in the mirror order of startup.',
  },
];

export default function LifecycleTimeline() {
  const [bean, setBean] = useState<BeanShape>({
    aware: true,
    postConstruct: true,
    initializingBean: true,
    customInit: true,
    advised: true,
    scope: 'singleton',
  });
  const [open, setOpen] = useState<string | null>(null);

  const set = <K extends keyof BeanShape>(key: K) => (value: BeanShape[K]) =>
    setBean((prev) => ({ ...prev, [key]: value }));

  const yesNo = [
    { label: 'no', value: false },
    { label: 'yes', value: true },
  ];

  const firing = STEPS.filter((s) => s.when(bean));
  let phase: Phase | null = null;

  return (
    <div className="w">
      <div className="widget-head">
        <span className="widget-title">What happens to your bean, in order</span>
        <span className="widget-sub">AbstractAutowireCapableBeanFactory.doCreateBean</span>
      </div>

      <div className="ctl-row">
        <ToggleGroup label="scope" options={[{ label: 'singleton', value: 'singleton' as const }, { label: 'prototype', value: 'prototype' as const }]} value={bean.scope} onChange={set('scope')} />
        <ToggleGroup label="implements *Aware" options={yesNo} value={bean.aware} onChange={set('aware')} />
        <ToggleGroup label="has @PostConstruct" options={yesNo} value={bean.postConstruct} onChange={set('postConstruct')} />
        <ToggleGroup label="InitializingBean" options={yesNo} value={bean.initializingBean} onChange={set('initializingBean')} />
        <ToggleGroup label="init-method" options={yesNo} value={bean.customInit} onChange={set('customInit')} />
        <ToggleGroup label="is advised" options={yesNo} value={bean.advised} onChange={set('advised')} />
      </div>

      <ol className="cp-list">
        {firing.map((step, i) => {
          const newPhase = step.phase !== phase;
          phase = step.phase;
          const isOpen = open === step.label;
          return (
            <li key={step.label}>
              {newPhase && <div className="lc-phase">{PHASES[step.phase]}</div>}
              <div className={isOpen ? 'cp-step open' : 'cp-step'}>
                <div className="cp-rail">
                  <div className="cp-num">{i + 1}</div>
                  {i < firing.length - 1 && <div className="cp-line" />}
                </div>
                <div className="cp-body">
                  <button
                    type="button"
                    className="cp-head"
                    aria-expanded={isOpen}
                    onClick={() => setOpen(isOpen ? null : step.label)}
                  >
                    <span className="cp-cls">{step.label}</span>
                  </button>
                  {isOpen && <p className="cp-notice">{step.detail}</p>}
                </div>
              </div>
            </li>
          );
        })}
      </ol>

      {bean.scope === 'prototype' && (
        <div className="outcome bad">
          <span className="lbl">note</span>
          <span className="why">
            A prototype gets no destruction callbacks at all, and no early reference, so it can
            never take part in a circular reference either.
          </span>
        </div>
      )}
    </div>
  );
}
