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
    id: 'web',
    title: 'Web',
    blurb: 'What happens between the socket and your controller method, and on the way back.',
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
  {
    id: 'testing',
    title: 'Testing',
    blurb: 'Why the suite is slow, and whether it is testing what you think it is.',
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
    status: 'shipped',
    lab: 'lab-lifecycle',
    tests: 14,
  },
  {
    slug: 'startup',
    title: 'Container startup phases',
    track: 'foundations',
    blurb: 'What refresh() does in order, where SmartLifecycle fits, and the bean that misses every BeanPostProcessor.',
    status: 'shipped',
    lab: 'lab-startup',
    tests: 13,
  },

  {
    slug: 'property-binding',
    title: 'Property binding',
    track: 'configuration',
    blurb: '@ConfigurationProperties, relaxed binding and converters: how a string becomes a typed object, and where @Value stops.',
    status: 'shipped',
    lab: 'lab-binding',
    tests: 24,
  },
  {
    slug: 'environment',
    title: 'Environment and profiles',
    track: 'configuration',
    blurb: 'PropertySource precedence, profile expressions and placeholder resolution. Why that property is not what you set it to.',
    status: 'shipped',
    lab: 'lab-environment',
    tests: 17,
  },
  {
    slug: 'conditions',
    title: 'Conditions and auto-configuration',
    track: 'configuration',
    blurb: '@Conditional evaluation, why @ConditionalOnMissingBean works in auto-configuration and not in yours, and reading the --debug report.',
    status: 'shipped',
    lab: 'lab-conditions',
    tests: 16,
  },

  {
    slug: 'web-mvc',
    title: 'The request lifecycle',
    track: 'web',
    blurb: 'DispatcherServlet end to end: handler mapping, argument resolution, message converters and @ExceptionHandler resolution.',
    status: 'shipped',
    lab: 'lab-web',
    tests: 24,
  },
  {
    slug: 'rest-clients',
    title: 'Calling other services',
    track: 'web',
    blurb: 'RestClient and the HTTP interface clients: timeouts that are not set by default, error decoding, and connection pools.',
    status: 'next',
  },
  {
    slug: 'security',
    title: 'The security filter chain',
    track: 'web',
    blurb: 'Where authentication actually happens, and why it is a filter rather than an interceptor.',
    status: 'queued',
  },

  {
    slug: 'transactions',
    title: '@Transactional',
    track: 'data',
    blurb: 'The seven propagation modes proved by what survives in the table, rollback rules, and the rollback-only trap.',
    status: 'shipped',
    lab: 'lab-transactions',
    tests: 28,
  },
  {
    slug: 'jdbctemplate',
    title: 'JdbcTemplate and DataSource',
    track: 'data',
    blurb: 'Where the connection comes from, exception translation, and the three query APIs.',
    status: 'shipped',
    lab: 'lab-jdbc',
    tests: 18,
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
    status: 'shipped',
    lab: 'lab-async',
    tests: 14,
  },
  {
    slug: 'retry',
    title: 'Retry and concurrency limits',
    track: 'execution',
    blurb: 'Framework 7 has retry built in. Backoff, jitter, @ConcurrencyLimit, and how it differs from the spring-retry library.',
    status: 'shipped',
    lab: 'lab-retry',
    tests: 10,
  },
  {
    slug: 'caching',
    title: 'Caching',
    track: 'execution',
    blurb: 'Why two @Cacheable methods can share entries, condition versus unless, and the two ways caching silently does nothing.',
    status: 'shipped',
    lab: 'lab-caching',
    tests: 13,
  },
  {
    slug: 'virtual-threads',
    title: 'Virtual threads',
    track: 'execution',
    blurb: 'What spring.threads.virtual.enabled actually swaps out, and what still pins a carrier thread.',
    status: 'queued',
  },

  {
    slug: 'testing',
    title: 'The test context cache',
    track: 'testing',
    blurb: 'Why the suite is slow, what is in the cache key, and the commit a @Transactional test never performs.',
    status: 'shipped',
    lab: 'lab-testing',
    tests: 14,
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
