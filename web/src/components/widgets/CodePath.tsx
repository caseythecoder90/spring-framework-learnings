import { useState } from 'react';

export interface CodePathStep {
  module: string;
  class: string;
  method: string;
  notice: string;
  repo: 'framework' | 'boot';
  file?: string;
}

export interface CodePathData {
  title: string;
  summary: string;
  entry: string;
  steps: CodePathStep[];
}

const REPOS = {
  framework: { owner: 'spring-projects/spring-framework', tag: 'v7.0.9' },
  boot: { owner: 'spring-projects/spring-boot', tag: 'v4.1.1' },
} as const;

function simpleName(fqcn: string): string {
  const last = fqcn.slice(fqcn.lastIndexOf('.') + 1);
  return last.replace(/\$/g, '.');
}

function packageName(fqcn: string): string {
  const i = fqcn.lastIndexOf('.');
  return i < 0 ? '' : fqcn.slice(0, i);
}

/**
 * Inner classes live in their outer class's file, so CglibAopProxy$DynamicAdvisedInterceptor
 * resolves to CglibAopProxy.java.
 */
function sourceUrl(step: CodePathStep): string {
  const repo = REPOS[step.repo] ?? REPOS.framework;
  if (step.file) return `https://github.com/${repo.owner}/blob/${repo.tag}/${step.file}`;
  const topLevel = step.class.split('$')[0]!;
  const path = topLevel.replace(/\./g, '/');
  return `https://github.com/${repo.owner}/blob/${repo.tag}/${step.module}/src/main/java/${path}.java`;
}

export default function CodePath({ data }: { data: CodePathData }) {
  const [open, setOpen] = useState<number | null>(0);

  return (
    <div className="w">
      <div className="widget-head">
        <span className="widget-title">{data.title}</span>
        <span className="widget-sub">{data.steps.length} stops</span>
      </div>

      <p className="cp-entry">
        <b>Start here:</b> {data.entry}
      </p>

      <ol className="cp-list">
        {data.steps.map((step, i) => {
          const isOpen = open === i;
          const isLast = i === data.steps.length - 1;
          return (
            <li key={`${step.class}#${step.method}`} className={isOpen ? 'cp-step open' : 'cp-step'}>
              <div className="cp-rail">
                <div className="cp-num">{i + 1}</div>
                {!isLast && <div className="cp-line" />}
              </div>
              <div className="cp-body">
                <button
                  type="button"
                  className="cp-head"
                  aria-expanded={isOpen}
                  onClick={() => setOpen(isOpen ? null : i)}
                >
                  <span className="cp-cls">
                    {simpleName(step.class)}.{step.method}()
                  </span>
                  <span className="cp-mod">{step.module}</span>
                </button>
                {isOpen && (
                  <>
                    <div className="cp-pkg">{packageName(step.class)}</div>
                    <p className="cp-notice">{step.notice}</p>
                    <div className="cp-links">
                      <a href={sourceUrl(step)} target="_blank" rel="noopener">
                        Read the source
                      </a>
                    </div>
                  </>
                )}
              </div>
            </li>
          );
        })}
      </ol>
    </div>
  );
}
