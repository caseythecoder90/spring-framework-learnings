import { defineCollection, z } from 'astro:content';
import { glob } from 'astro/loaders';

/**
 * The notes live in docs/ as plain markdown so they stay readable on GitHub.
 * README and TEMPLATE are repo furniture, not topics.
 */
const notes = defineCollection({
  loader: glob({ pattern: ['*.md', '!README.md', '!TEMPLATE.md'], base: '../docs' }),
});

const step = z.object({
  /** Maven module the class lives in, e.g. spring-tx. Used to build the source link. */
  module: z.string(),
  /** Fully qualified class name. Inner classes use $, as the JVM writes them. */
  class: z.string(),
  /** Method to stop at. Overloads are not distinguished; any match counts. */
  method: z.string(),
  /** What to look at once you are in there. */
  notice: z.string(),
  repo: z.enum(['framework', 'boot']).default('framework'),
  /** Escape hatch when the source path is not derivable from the package. */
  file: z.string().optional(),
});

/**
 * Guided source-reading traces. Every class and method here is checked against the real
 * Spring jars by CodePathsAreRealTest in labs/lab-codepaths, so this cannot rot silently.
 */
const paths = defineCollection({
  loader: glob({ pattern: '*.json', base: '../paths' }),
  schema: z.object({
    title: z.string(),
    summary: z.string(),
    /** Where you would put the first breakpoint. */
    entry: z.string(),
    /** Which note this path belongs to, matching a notes collection id. */
    note: z.string().optional(),
    steps: z.array(step).min(2),
  }),
});

export const collections = { notes, paths };
