import { visit } from 'unist-util-visit';

const MARKER = /^\s*<!--\s*widget:([a-z-]+)(?::([a-z0-9-]+))?\s*-->\s*$/i;

/**
 * Turns an invisible HTML comment in a plain .md note into a mount point for a React island:
 *
 *   <!-- widget:scheduler-routing -->        -> <div data-widget="scheduler-routing">
 *   <!-- widget:path:transactional-call -->  -> <div data-widget="path" data-arg="transactional-call">
 *
 * GitHub renders the comment as nothing, so docs/*.md stays readable in the repo.
 */
export default function remarkWidgetMarkers() {
  return (tree) => {
    visit(tree, 'html', (node) => {
      const match = MARKER.exec(node.value ?? '');
      if (!match) return;
      const name = match[1];
      const arg = match[2];
      node.value = arg
        ? `<div class="widget-slot" data-widget="${name}" data-arg="${arg}"></div>`
        : `<div class="widget-slot" data-widget="${name}"></div>`;
    });
  };
}
