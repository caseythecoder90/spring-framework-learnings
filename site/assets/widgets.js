window.Widgets = window.Widgets || {};

function segmented(label, options, value, onChange) {
  var wrap = document.createElement('div');
  wrap.className = 'ctl';
  var lbl = document.createElement('div');
  lbl.className = 'ctl-label';
  lbl.textContent = label;
  var seg = document.createElement('div');
  seg.className = 'seg';
  options.forEach(function (opt) {
    var b = document.createElement('button');
    b.type = 'button';
    b.textContent = opt.label;
    b.setAttribute('aria-pressed', String(opt.value === value));
    b.addEventListener('click', function () { onChange(opt.value); });
    seg.appendChild(b);
  });
  wrap.appendChild(lbl);
  wrap.appendChild(seg);
  return wrap;
}

function head(host, title, sub) {
  var h = document.createElement('div');
  h.className = 'widget-head';
  h.innerHTML = '<span class="widget-title"></span><span class="widget-sub"></span>';
  h.querySelector('.widget-title').textContent = title;
  h.querySelector('.widget-sub').textContent = sub;
  host.appendChild(h);
}

window.Widgets['scheduler-routing'] = function (host) {
  var state = { qualifier: false, ts: 1, tsNamed: false, tsPrimary: false, ses: 0, sesNamed: false };

  var STEPS = [
    'Qualifier on @Scheduled(scheduler = "...")',
    'Unique TaskScheduler bean, by type',
    'Several TaskSchedulers: the one named taskScheduler',
    'No TaskScheduler at all: unique ScheduledExecutorService',
    'Several of those: the one named taskScheduler',
    'Executors.newSingleThreadScheduledExecutor()'
  ];

  function resolve(s) {
    if (s.qualifier) {
      return { step: 1, reached: [1], outcome: 'the bean named reportScheduler',
        why: 'The qualifier short-circuits the whole lookup.', bad: false };
    }
    if (s.ts === 1) {
      return { step: 2, reached: [1, 2], outcome: 'your single TaskScheduler bean',
        why: 'One candidate by type, so resolution succeeds immediately.', bad: false };
    }
    if (s.ts >= 2) {
      if (s.tsPrimary) {
        return { step: 2, reached: [1, 2], outcome: 'the @Primary TaskScheduler bean',
          why: '@Primary makes by-type resolution unique again.', bad: false };
      }
      if (s.tsNamed) {
        return { step: 3, reached: [1, 2, 3], outcome: 'the bean named taskScheduler',
          why: 'NoUniqueBeanDefinitionException, then a by-name lookup that hits.', bad: false };
      }
      return { step: 6, reached: [1, 2, 3, 6], outcome: 'a private single-thread executor',
        why: 'Several TaskSchedulers and none named taskScheduler. The ScheduledExecutorService branch is never reached on this path — it only runs when there were zero TaskScheduler beans. All you get is one INFO line.',
        bad: true };
    }
    if (s.ses === 1) {
      return { step: 4, reached: [1, 2, 4], outcome: 'your ScheduledExecutorService, wrapped in ConcurrentTaskScheduler',
        why: 'No TaskScheduler bean, so the search falls through to executors.', bad: false };
    }
    if (s.ses >= 2) {
      if (s.sesNamed) {
        return { step: 5, reached: [1, 2, 4, 5], outcome: 'the ScheduledExecutorService named taskScheduler',
          why: 'Not unique by type, resolved by name instead.', bad: false };
      }
      return { step: 6, reached: [1, 2, 4, 5, 6], outcome: 'a private single-thread executor',
        why: 'Several executors and none named taskScheduler.', bad: true };
    }
    return { step: 6, reached: [1, 2, 4, 6], outcome: 'a private single-thread executor',
      why: 'Nothing to schedule on, so Spring quietly makes its own one-thread pool. This is the Boot default path before TaskSchedulingAutoConfiguration contributes a scheduler.',
      bad: true };
  }

  head(host, 'Which scheduler runs your task?', 'TaskSchedulerRouter.determineDefaultScheduler()');

  var controls = document.createElement('div');
  controls.className = 'ctl-row';
  var list = document.createElement('ul');
  list.className = 'steps';
  var outcome = document.createElement('div');
  outcome.className = 'outcome';
  host.appendChild(controls);
  host.appendChild(list);
  host.appendChild(outcome);

  function set(key) {
    return function (v) { state[key] = v; render(); };
  }

  function render() {
    var r = resolve(state);

    controls.innerHTML = '';
    controls.appendChild(segmented('@Scheduled qualifier',
      [{ label: 'none', value: false }, { label: 'reportScheduler', value: true }],
      state.qualifier, set('qualifier')));

    var tsCtl = segmented('TaskScheduler beans',
      [{ label: '0', value: 0 }, { label: '1', value: 1 }, { label: '2+', value: 2 }],
      state.ts, set('ts'));
    if (state.qualifier) tsCtl.classList.add('disabled');
    controls.appendChild(tsCtl);

    var namedCtl = segmented('one named taskScheduler',
      [{ label: 'no', value: false }, { label: 'yes', value: true }],
      state.tsNamed, set('tsNamed'));
    if (state.qualifier || state.ts < 2) namedCtl.classList.add('disabled');
    controls.appendChild(namedCtl);

    var primaryCtl = segmented('one is @Primary',
      [{ label: 'no', value: false }, { label: 'yes', value: true }],
      state.tsPrimary, set('tsPrimary'));
    if (state.qualifier || state.ts < 2) primaryCtl.classList.add('disabled');
    controls.appendChild(primaryCtl);

    var sesCtl = segmented('ScheduledExecutorService beans',
      [{ label: '0', value: 0 }, { label: '1', value: 1 }, { label: '2+', value: 2 }],
      state.ses, set('ses'));
    if (state.qualifier || state.ts !== 0) sesCtl.classList.add('disabled');
    controls.appendChild(sesCtl);

    var sesNamedCtl = segmented('one named taskScheduler',
      [{ label: 'no', value: false }, { label: 'yes', value: true }],
      state.sesNamed, set('sesNamed'));
    if (state.qualifier || state.ts !== 0 || state.ses < 2) sesNamedCtl.classList.add('disabled');
    controls.appendChild(sesNamedCtl);

    list.innerHTML = '';
    STEPS.forEach(function (text, i) {
      var n = i + 1;
      var li = document.createElement('li');
      var reached = r.reached.indexOf(n) !== -1;
      if (n === r.step) li.className = 'taken' + (r.bad ? ' bad' : '');
      else if (!reached) li.className = 'unreachable';
      li.innerHTML = '<span class="n"></span><span class="txt"></span>';
      li.querySelector('.n').textContent = n;
      li.querySelector('.txt').textContent = text;
      if (!reached) {
        var note = document.createElement('span');
        note.className = 'note';
        note.textContent = 'not reached';
        li.appendChild(note);
      }
      list.appendChild(li);
    });

    outcome.className = 'outcome' + (r.bad ? ' bad' : '');
    outcome.innerHTML = '<span class="lbl">your task runs on</span>'
      + '<span class="val"></span><span class="why"></span>';
    outcome.querySelector('.val').textContent = r.outcome;
    outcome.querySelector('.why').textContent = r.why;
  }

  render();
};

window.Widgets['transaction-phases'] = function (host) {
  var outcomeMode = 'commit';

  var LISTENERS = [
    { name: '@EventListener', when: function () { return 0.34; } },
    { name: 'BEFORE_COMMIT', when: function (m) { return m === 'commit' ? 0.66 : false; } },
    { name: 'AFTER_COMMIT (default)', when: function (m) { return m === 'commit' ? 0.82 : false; } },
    { name: 'AFTER_ROLLBACK', when: function (m) { return m === 'rollback' ? 0.82 : false; } },
    { name: 'AFTER_COMPLETION', when: function (m) { return m === 'none' ? false : 0.9; } },
    { name: 'AFTER_COMMIT, fallbackExecution', when: function (m) {
        if (m === 'commit') return 0.82;
        if (m === 'none') return 0.34;
        return false;
      } }
  ];

  var MARKS = {
    commit: [{ at: 0.12, text: 'method begins' }, { at: 0.34, text: 'publishEvent' },
             { at: 0.58, text: 'method returns' }, { at: 0.74, text: 'commit' }],
    rollback: [{ at: 0.12, text: 'method begins' }, { at: 0.34, text: 'publishEvent' },
               { at: 0.58, text: 'throws' }, { at: 0.74, text: 'rollback' }],
    none: [{ at: 0.34, text: 'publishEvent' }]
  };

  head(host, 'Which listeners actually fire?', 'TransactionalApplicationListenerMethodAdapter');

  var controls = document.createElement('div');
  controls.className = 'ctl-row';
  var txbar = document.createElement('div');
  txbar.className = 'txbar';
  var lanes = document.createElement('div');
  lanes.className = 'lanes';
  var axis = document.createElement('div');
  axis.className = 'axis';
  var outcome = document.createElement('div');
  outcome.className = 'outcome';
  host.appendChild(controls);
  host.appendChild(txbar);
  host.appendChild(lanes);
  host.appendChild(axis);
  host.appendChild(outcome);

  var timer = null;

  function render(animate) {
    if (timer) { clearTimeout(timer); timer = null; }

    controls.innerHTML = '';
    controls.appendChild(segmented('transaction outcome',
      [{ label: 'commit', value: 'commit' }, { label: 'rollback', value: 'rollback' },
       { label: 'no transaction', value: 'none' }],
      outcomeMode, function (v) { outcomeMode = v; render(true); }));

    var replay = document.createElement('div');
    replay.className = 'ctl';
    replay.innerHTML = '<div class="ctl-label">&nbsp;</div>';
    var rb = document.createElement('button');
    rb.type = 'button';
    rb.className = 'ghost';
    rb.textContent = 'Replay';
    rb.addEventListener('click', function () { render(true); });
    replay.appendChild(rb);
    controls.appendChild(replay);

    txbar.innerHTML = '';
    if (outcomeMode !== 'none') {
      var span = document.createElement('div');
      span.className = 'span' + (outcomeMode === 'rollback' ? ' rolled' : '');
      span.style.left = '12%';
      span.style.width = '62%';
      txbar.appendChild(span);
    }

    lanes.innerHTML = '';
    var dots = [];
    LISTENERS.forEach(function (l) {
      var firePoint = l.when(outcomeMode);
      var willFire = firePoint !== false;
      var lane = document.createElement('div');
      lane.className = 'lane' + (willFire ? '' : ' skipped');
      var name = document.createElement('div');
      name.className = 'lane-name';
      name.textContent = l.name;
      var track = document.createElement('div');
      track.className = 'lane-track';
      if (willFire) {
        var dot = document.createElement('div');
        dot.className = 'lane-dot';
        dot.style.left = (firePoint * 100) + '%';
        track.appendChild(dot);
        dots.push({ el: dot, at: firePoint });
      } else {
        var tag = document.createElement('div');
        tag.className = 'lane-tag';
        tag.textContent = outcomeMode === 'none' ? 'silently skipped' : 'wrong phase';
        track.appendChild(tag);
      }
      lane.appendChild(name);
      lane.appendChild(track);
      lanes.appendChild(lane);
    });

    axis.innerHTML = '';
    MARKS[outcomeMode].forEach(function (m) {
      var tick = document.createElement('div');
      tick.className = 'tick';
      tick.style.left = (m.at * 100) + '%';
      var label = document.createElement('div');
      label.className = 'mark';
      label.style.left = (m.at * 100) + '%';
      label.textContent = m.text;
      axis.appendChild(tick);
      axis.appendChild(label);
    });

    var bad = outcomeMode === 'none';
    outcome.className = 'outcome' + (bad ? ' bad' : '');
    var text = {
      commit: 'Everything after publishEvent waits for the commit, which happens after your method has already returned.',
      rollback: 'The AFTER_COMMIT listener never runs. That is usually exactly what you wanted.',
      none: 'No transaction to register a synchronization on, so every @TransactionalEventListener is skipped. The only trace is a DEBUG log: "No transaction is active - skipping". Only fallbackExecution = true still runs.'
    }[outcomeMode];
    outcome.innerHTML = '<span class="lbl">what happens</span><span class="why"></span>';
    outcome.querySelector('.why').textContent = text;

    if (animate !== false) {
      dots.sort(function (a, b) { return a.at - b.at; });
      dots.forEach(function (d, i) {
        setTimeout(function () { d.el.classList.add('fired'); }, 220 + i * 320);
      });
    } else {
      dots.forEach(function (d) { d.el.classList.add('fired'); });
    }
  }

  render(true);
};
