import { defineConfig } from 'astro/config';
import react from '@astrojs/react';
import { unified } from '@astrojs/markdown-remark';
import remarkWidgetMarkers from './src/plugins/remark-widget-markers.mjs';

export default defineConfig({
  site: 'https://caseythecoder90.github.io',
  base: '/spring-framework-learnings',
  trailingSlash: 'ignore',
  integrations: [react()],
  markdown: {
    processor: unified({ remarkPlugins: [remarkWidgetMarkers] }),
    shikiConfig: {
      themes: { light: 'github-light', dark: 'github-dark' },
      wrap: false,
    },
  },
  vite: {
    server: { fs: { allow: ['..'] } },
  },
});
