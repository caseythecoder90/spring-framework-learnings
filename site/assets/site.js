(function () {
  var root = document.documentElement;

  function currentTheme() {
    return root.getAttribute('data-theme')
      || (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
  }

  function applyTheme(theme) {
    root.setAttribute('data-theme', theme);
    try { localStorage.setItem('theme', theme); } catch (e) { /* private mode */ }
    var btn = document.getElementById('theme');
    if (btn) btn.textContent = theme === 'dark' ? 'Light' : 'Dark';
  }

  try {
    var saved = localStorage.getItem('theme');
    if (saved === 'dark' || saved === 'light') root.setAttribute('data-theme', saved);
  } catch (e) { /* private mode */ }

  document.addEventListener('DOMContentLoaded', function () {
    var btn = document.getElementById('theme');
    if (btn) {
      btn.textContent = currentTheme() === 'dark' ? 'Light' : 'Dark';
      btn.addEventListener('click', function () {
        applyTheme(currentTheme() === 'dark' ? 'light' : 'dark');
      });
    }
    var article = document.querySelector('article[data-note]');
    if (article) loadNote(article);
  });

  function slug(text) {
    return text.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');
  }

  function loadNote(article) {
    var name = article.getAttribute('data-note');
    fetch('notes/' + name + '.md')
      .then(function (r) {
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return r.text();
      })
      .then(function (md) {
        article.innerHTML = window.marked.parse(md);
        rewriteRepoLinks(article);
        mountWidgets(article);
        highlight(article);
        buildToc(article);
      })
      .catch(function (err) {
        article.innerHTML = '<p class="loading">Could not load notes/' + name + '.md (' + err.message
          + '). This page reads the markdown over HTTP, so it needs a server: '
          + '<code>./tools/build-site.sh --serve</code></p>';
      });
  }

  var REPO = 'https://github.com/caseythecoder90/spring-framework-learnings/blob/main/';

  function rewriteRepoLinks(article) {
    article.querySelectorAll('a[href]').forEach(function (a) {
      var href = a.getAttribute('href');
      if (/^(https?:|#|mailto:)/.test(href)) return;
      var target = href.replace(/^(\.\.\/)+/, '');
      a.setAttribute('href', REPO + target);
      a.setAttribute('target', '_blank');
      a.setAttribute('rel', 'noopener');
    });
  }

  function mountWidgets(article) {
    var walker = document.createTreeWalker(article, NodeFilter.SHOW_COMMENT);
    var found = [];
    var node;
    while ((node = walker.nextNode())) {
      var m = /^\s*widget:([a-z-]+)\s*$/.exec(node.nodeValue || '');
      if (m) found.push({ node: node, name: m[1] });
    }
    found.forEach(function (hit) {
      var host = document.createElement('div');
      host.className = 'widget';
      hit.node.parentNode.replaceChild(host, hit.node);
      var build = window.Widgets && window.Widgets[hit.name];
      if (build) build(host);
      else host.innerHTML = '<p class="loading">Unknown widget: ' + hit.name + '</p>';
    });
  }

  function highlight(article) {
    if (!window.hljs) return;
    article.querySelectorAll('pre code').forEach(function (block) {
      try { window.hljs.highlightElement(block); } catch (e) { /* unknown language */ }
    });
  }

  function buildToc(article) {
    var toc = document.querySelector('aside.toc');
    if (!toc) return;
    var heads = article.querySelectorAll('h2');
    if (!heads.length) return;
    var html = '';
    heads.forEach(function (h) {
      if (!h.id) h.id = slug(h.textContent);
      html += '<a href="#' + h.id + '">' + h.textContent + '</a>';
    });
    toc.innerHTML = html;

    var links = Array.prototype.slice.call(toc.querySelectorAll('a'));
    var observer = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (!entry.isIntersecting) return;
        links.forEach(function (l) {
          l.classList.toggle('active', l.getAttribute('href') === '#' + entry.target.id);
        });
      });
    }, { rootMargin: '-80px 0px -70% 0px' });
    heads.forEach(function (h) { observer.observe(h); });
  }
})();
