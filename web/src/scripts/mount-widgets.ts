import { createElement, type ComponentType } from 'react';
import { createRoot } from 'react-dom/client';
import type { CodePathData } from '../components/widgets/CodePath';

/**
 * The notes are plain markdown, so widgets cannot be JSX in the source. The remark plugin leaves
 * a <div data-widget="..."> behind and this mounts a React root into each one. Dynamic imports
 * keep a widget's code off pages that do not use it.
 */
const REGISTRY: Record<string, () => Promise<{ default: ComponentType }>> = {
  'scheduler-routing': () => import('../components/widgets/SchedulerRouting'),
  'transaction-phases': () => import('../components/widgets/TransactionPhases'),
  'lifecycle-timeline': () => import('../components/widgets/LifecycleTimeline'),
};

function inlinePaths(): Record<string, CodePathData> {
  const el = document.getElementById('__paths');
  if (!el?.textContent) return {};
  try {
    return JSON.parse(el.textContent) as Record<string, CodePathData>;
  } catch {
    return {};
  }
}

function fail(el: HTMLElement, message: string): void {
  el.textContent = message;
  el.setAttribute('style', 'font-family:var(--mono);font-size:13px;color:var(--danger)');
}

async function mount(el: HTMLElement): Promise<void> {
  const name = el.dataset.widget;
  if (!name) return;

  if (name === 'path') {
    const id = el.dataset.arg ?? '';
    const data = inlinePaths()[id];
    if (!data) {
      fail(el, `Unknown code path: ${id}`);
      return;
    }
    const { default: CodePath } = await import('../components/widgets/CodePath');
    createRoot(el).render(createElement(CodePath, { data }));
    return;
  }

  const load = REGISTRY[name];
  if (!load) {
    fail(el, `Unknown widget: ${name}`);
    return;
  }
  const { default: Widget } = await load();
  createRoot(el).render(createElement(Widget));
}

document.querySelectorAll<HTMLElement>('[data-widget]').forEach((el) => {
  void mount(el);
});
