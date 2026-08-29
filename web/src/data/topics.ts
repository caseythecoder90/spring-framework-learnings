/**
 * The map. Notes stay as plain markdown in docs/ with no frontmatter, so ordering and status
 * live here instead — one typed place, rather than YAML smeared across fifteen files.
 */

export const TRACKS = [
  {
    id: 'foundations',
    title: 'Foundations',
    blurb: 'Explains every other track. Why @Transactional self-invocation fails lives here, not in Data.',
  },
  {
    id: 'configuration',
    title: 'Configuration',
    blurb: 'How text in a file becomes a typed object, and what decides which beans exist.',
  },
  {
    id: 'data',
    title: 'Data',
    blurb: 'The stack you actually debug at work, bottom up.',
  },
  {
    id: 'execution',
    title: 'Execution',
    blurb: 'Work that happens off the request thread, or later, or again.',
  },
] as const;

export type TrackId = (typeof TRACKS)[number]['id'];
export type Status = 'shipped' | 'next' | 'queued';

export interface Topic {
  /** Matches the docs/<slug>.md filename and the notes collection id. */
  slug: string;
  title: string;
  track: TrackId;
  blurb: string;
  status: Status;
  lab?: string;
  tests?: number;
}

export const TOPICS: Topic[] = [
  {
    slug: 'annotations',
    title: 'The annotation model',
    track: 'foundations',
    blurb: 'MergedAnnotations, meta-annotations and @AliasFor: how Spring finds an annotation you never wrote directly.',
    status: 'shipped',
    lab: 'lab-annotations',
    tests: 18,
  },
  {
    slug: 'proxies',
    title: 'The proxy model',
    track: 'foundations',
    blurb: 'JDK versus CGLIB, why self-invocation skips your advice, and how proxy ordering is decided.',
    status: 'shipped',
    lab: 'lab-proxies',
    tests: 18,
  },
  {
    slug: 'bean-lifecycle',
    title: 'Bean lifecycle and DI',
    track: 'foundations',
    blurb: 'Constructor versus field injection, BeanPostProcessor ordering, and circular references.',
    status: 'next',
  },
  {
    slug: 'startup',
    title: 'Container startup phases',
    track: 'foundations',
    blurb: 'What refresh() does in order, and where SmartLifecycle fits.',
    status: 'queued',
  },

  {
    slug: 'property-binding',
    title: 'Property binding',
    track: 'configuration',
    blurb: '@ConfigurationProperties, relaxed binding and converters: how a string becomes a typed object.',
    status: 'queued',
  },
  {
    slug: 'environment',
    title: 'Environment and profiles',
    track: 'configuration',
    blurb: 'PropertySource precedence and placeholder resolution.',
    status: 'queued',
  },
  {
    slug: 'conditions',
    title: 'Conditions and auto-configuration',
    track: 'configuration',
    blurb: '@Conditional evaluation, auto-configuration ordering, and testing it with ApplicationContextRunner.',
    status: 'queued',
  },

  {
    slug: 'jdbctemplate',
    title: 'JdbcTemplate and DataSource',
    track: 'data',
    blurb: 'Connection handling, pooling, and how a vendor SQLException becomes a DataAccessException.',
    status: 'queued',
  },
  {
    slug: 'transactions',
    title: 'Transaction propagation',
    track: 'data',
    blurb: 'The seven propagation modes as a matrix, and what each one does to an existing transaction.',
    status: 'queued',
  },
  {
    slug: 'isolation',
    title: 'Isolation and anomalies',
    track: 'data',
    blurb: 'Dirty reads, non-repeatable reads and phantoms, and which isolation level actually prevents which.',
    status: 'queued',
  },
  {
    slug: 'hibernate',
    title: 'Hibernate persistence context',
    track: 'data',
    blurb: 'Flush timing, dirty checking, lazy loading and where N+1 comes from.',
    status: 'queued',
  },

  {
    slug: 'scheduling',
    title: 'Scheduling',
    track: 'execution',
    blurb: 'How @Scheduled is wired, which thread runs it, and how it fails.',
    status: 'shipped',
    lab: 'lab-scheduling',
    tests: 14,
  },
  {
    slug: 'events',
    title: 'Application events',
    track: 'execution',
    blurb: 'The publish path, listener registration, and what @Async and transactions change.',
    status: 'shipped',
    lab: 'lab-events',
    tests: 15,
  },
  {
    slug: 'async',
    title: '@Async and executors',
    track: 'execution',
    blurb: 'Pool configuration, CompletableFuture, and propagating context across the hand-off.',
    status: 'queued',
  },
  {
    slug: 'retry',
    title: 'Spring Retry',
    track: 'execution',
    blurb: 'Backoff policies, recovery methods, and what happens when retry meets a transaction.',
    status: 'queued',
  },
  {
    slug: 'caching',
    title: 'Caching',
    track: 'execution',
    blurb: 'Key generation, cache resolution, and self-invocation biting a second time.',
    status: 'queued',
  },
];

export const GUIDES = [
  {
    slug: 'reading-the-source',
    title: 'How to read Spring source',
    blurb: 'A method for finding the entry point of any Spring feature, and the five shapes it will turn out to be.',
  },
];

export function topicsInTrack(track: TrackId): Topic[] {
  return TOPICS.filter((t) => t.track === track);
}

export function topicBySlug(slug: string): Topic | undefined {
  return TOPICS.find((t) => t.slug === slug);
}
