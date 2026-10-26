// SafeTube parent screens: PIN, first-run setup and settings.
//
// Runs in the app's own WebView (never on YouTube). Everything it reads or changes goes through
// the SafeTubeParent bridge in ParentActivity. Text that came from YouTube (titles, channel names)
// is always escaped before it touches the page.
(() => {
  'use strict';

  const B = window.SafeTubeParent;
  const app = document.getElementById('app');
  let S = JSON.parse(B.state()); // mode, settings, current page, defaults
  let L = { stats: {}, hidden: [], blocked: [], allowed: [], channels: [] };
  let page = 'overview';

  // ------------------------------------------------------------------ helpers

  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

  const ICONS = {
    lock: '<rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/>',
    back: '<path d="M15 18l-6-6 6-6"/>',
    next: '<path d="M5 12h14M13 6l6 6-6 6"/>',
    check: '<path d="M5 12l5 5 9-10"/>',
    del: '<path d="M21 5H9l-6 7 6 7h12a1 1 0 0 0 1-1V6a1 1 0 0 0-1-1z"/><path d="M17 9l-5 6M12 9l5 6"/>',
    grid: '<rect x="3" y="3" width="7" height="7" rx="2"/><rect x="14" y="3" width="7" height="7" rx="2"/><rect x="3" y="14" width="7" height="7" rx="2"/><rect x="14" y="14" width="7" height="7" rx="2"/>',
    filter: '<path d="M3 5h18l-7 8v6l-4 2v-8z"/>',
    ban: '<circle cx="12" cy="12" r="9"/><path d="M5.6 5.6l12.8 12.8"/>',
    spark: '<path d="M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8z"/>',
    gear: '<circle cx="12" cy="12" r="3"/><path d="M12 2v3M12 19v3M4.2 4.2l2.1 2.1M17.7 17.7l2.1 2.1M2 12h3M19 12h3M4.2 19.8l2.1-2.1M17.7 6.3l2.1-2.1"/>',
    play: '<path d="M8 5v14l11-7z" fill="currentColor" stroke="none"/>',
    x: '<path d="M6 6l12 12M18 6L6 18"/>',
    shield: '<path d="M12 3l8 3v6c0 5-3.4 8.6-8 10-4.6-1.4-8-5-8-10V6z"/>',
    eyeoff: '<path d="M3 3l18 18"/><path d="M10.6 5.1A10.7 10.7 0 0 1 12 5c6 0 9.5 7 9.5 7a17.6 17.6 0 0 1-3 3.9M6.6 6.6C3.9 8.4 2.5 12 2.5 12S6 19 12 19c1.9 0 3.6-.7 5-1.6"/><path d="M9.9 9.9a3 3 0 0 0 4.2 4.2"/>',
    clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
    user: '<circle cx="12" cy="8" r="4"/><path d="M4 21c0-4 3.6-7 8-7s8 3 8 7"/>',
  };
  const icon = (name) => `<svg class="i" viewBox="0 0 24 24" aria-hidden="true">${ICONS[name]}</svg>`;
  const LOGO = '<svg viewBox="0 0 36 36" aria-hidden="true"><rect width="36" height="36" rx="10" fill="#0A7C6E"/>' +
    '<path d="M18 7l9 3.5v7c0 5.6-3.8 9.6-9 11.5-5.2-1.9-9-5.9-9-11.5v-7z" fill="#fff"/><path d="M15.5 13.5v8l6.5-4z" fill="#0A7C6E"/></svg>';
  const thumb = (id) => /^[A-Za-z0-9_-]{11}$/.test(id) ? `https://i.ytimg.com/vi/${id}/mqdefault.jpg` : '';

  // A thumbnail that can't load (offline, removed video) leaves a plain tile, not a broken image.
  document.addEventListener('error', (e) => {
    if (e.target && e.target.tagName === 'IMG') e.target.style.visibility = 'hidden';
  }, true);

  let toastTimer = 0;
  function toast(msg) {
    const t = document.getElementById('toast');
    t.textContent = msg;
    t.classList.add('on');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => t.classList.remove('on'), 2600);
  }

  // Slow work (AI calls, link lookups) runs in the app; the answer comes back through done().
  const pending = {};
  let nextId = 1;
  window.__parent = {
    done(id, json) {
      const resolve = pending[id];
      delete pending[id];
      if (resolve) resolve(JSON.parse(json));
    },
  };
  function call(name, args) {
    return new Promise((resolve) => {
      const id = nextId++;
      pending[id] = resolve;
      B.call(name, JSON.stringify(args || {}), id);
    });
  }

  function ensureSettings() {
    if (!S.settings) return;
    if (S.settings.blockOnAiFailure == null) S.settings.blockOnAiFailure = true;
    if (!S.settings.fallbackProvider) S.settings.fallbackProvider = { ...S.settings.provider, type: 'NONE', apiKey: '', accountId: '', model: '' };
  }

  function contentControls() {
    return `<label class="field-label" for="contentMode">Content mode</label>
      <select id="contentMode" class="input"><option value="hide" ${!S.settings.allowedContent ? 'selected' : ''}>Hide content matching my rules</option><option value="only" ${S.settings.allowedContent ? 'selected' : ''}>Only show content I describe</option></select>
      <label class="field-label" for="allowedContent">Content to show</label>
      <p class="note">Describe the content to allow, for example “Planet Coaster builds and educational science”. Everything outside this description is hidden. Requires a working smart checker.</p>
      <p class="note" id="content-error" role="alert"></p><textarea id="allowedContent" class="textarea" placeholder="Leave blank to check all content against your hide rules">${esc(S.settings.allowedContent || '')}</textarea>
      <label class="field-label" for="exceptions">Fine-tune the smart checker</label>
      <p class="note">For example, “Planet Coaster rides are fine unless there is swearing”. This adds context to AI decisions. Keywords and manually hidden videos or channels still take priority.</p>
      <textarea id="exceptions" class="textarea">${esc(S.settings.exceptions || '')}</textarea>`;
  }
  function wireContent(root) {
    const mode = root.querySelector('#contentMode');
    mode.addEventListener('change', () => {
      if (mode.value === 'hide') { root.querySelector('#allowedContent').value = ''; S.settings.allowedContent = ''; save('Content mode saved'); }
      else { root.querySelector('#content-error').textContent = 'Enter a description to activate Only show.'; root.querySelector('#allowedContent').focus(); }
    });
    root.querySelector('#allowedContent').addEventListener('input', (e) => { if (e.target.value.trim()) { mode.value = 'only'; root.querySelector('#content-error').textContent = ''; } });
    ['allowedContent', 'exceptions'].forEach((key) => root.querySelector('#' + key).addEventListener('change', (e) => {
      S.settings[key] = e.target.value.trim(); save('Rules saved');
    }));
  }

  function save(message) {
    const setupError = document.getElementById('checker-error');
    if (setupError) setupError.textContent = '';
    B.save(JSON.stringify(S.settings));
    if (message) toast(message);
  }

  function reloadLists() {
    L = JSON.parse(B.lists());
  }

  // ------------------------------------------------------------------ PIN pad

  // A PIN pad. check(pin) returns true (done), false (wrong) or 'more' (keep typing).
  function pinPad({ title, text, back, check }) {
    let pin = '';
    let fails = 0;
    let lockedUntil = 0;
    app.innerHTML = `
      <div class="pin-screen">
        <div class="pin-copy">
          <div class="pin-badge">${icon('lock')}</div>
          <h1>${esc(title)}</h1>
          <p>${esc(text)}</p>
          <div class="pin-dots" aria-live="polite"></div>
          <div class="pin-error"></div>
          ${back ? `<button class="pin-back" data-back>${icon('back')} ${esc(back.label)}</button>` : ''}
        </div>
        <div class="keypad">
          ${[1, 2, 3, 4, 5, 6, 7, 8, 9].map((n) => `<button data-k="${n}">${n}</button>`).join('')}
          <span></span><button data-k="0">0</button>
          <button class="key-del" data-del aria-label="Delete">${icon('del')}</button>
        </div>
      </div>`;
    const dots = app.querySelector('.pin-dots');
    const err = app.querySelector('.pin-error');
    const draw = () => {
      const n = Math.max(4, pin.length);
      dots.innerHTML = Array.from({ length: n }, (_, i) => `<span class="${i < pin.length ? 'on' : ''}"></span>`).join('');
      dots.setAttribute('aria-label', `${pin.length} digits entered`);
    };
    const wrong = (msg) => {
      fails++;
      pin = '';
      if (fails >= 5) {
        lockedUntil = Date.now() + 30000;
        fails = 0;
        msg = 'Too many tries. Wait 30 seconds.';
      }
      err.textContent = msg;
      dots.classList.remove('shake');
      void dots.offsetWidth;
      dots.classList.add('shake');
      draw();
    };
    app.querySelector('.keypad').addEventListener('click', (e) => {
      const b = e.target.closest('button');
      if (!b) return;
      if (Date.now() < lockedUntil) return;
      err.textContent = '';
      if (b.hasAttribute('data-del')) pin = pin.slice(0, -1);
      else if (pin.length < 8) pin += b.dataset.k;
      draw();
      if (pin.length < 4) return;
      const r = check(pin);
      if (r === true) return;
      if (r === 'more' && pin.length < 8) return;
      wrong(typeof r === 'string' && r !== 'more' ? r : 'That PIN isn’t right.');
    });
    const backBtn = app.querySelector('[data-back]');
    if (backBtn) backBtn.addEventListener('click', back.go);
    draw();
  }

  // Unlock: an existing PIN may be longer than 4 digits, so keep reading until it matches.
  function unlock(then, opts) {
    pinPad({
      title: opts.title,
      text: opts.text,
      back: { label: opts.backLabel || 'Back to videos', go: () => B.close() },
      check: (pin) => {
        if (B.checkPin(pin)) {
          S = JSON.parse(B.state()); // settings are only shared once the PIN is in
          then();
          return true;
        }
        // PINs made before the length was stored can be 4 to 8 digits long.
        return S.pinLength && pin.length >= S.pinLength ? false : 'more';
      },
    });
  }

  function createPin(then, { setup, back }) {
    let first = '';
    const ask = () => pinPad({
      title: first ? 'Once more' : (setup ? 'Create a parent PIN' : 'New PIN'),
      text: first ? 'Enter the same four digits again.' : 'Four digits your child doesn’t know. You’ll need it to change settings.',
      back: first ? { label: 'Start again', go: () => { first = ''; ask(); } } : back,
      check: (pin) => {
        if (!first) { first = pin; setTimeout(ask, 120); return true; }
        if (pin !== first) { first = ''; setTimeout(ask, 900); return 'Those didn’t match. Let’s try again.'; }
        B.setPin(pin);
        S = JSON.parse(B.state());
        then();
        return true;
      },
    });
    ask();
  }

  // ------------------------------------------------------------------ first-run setup

  const PRESETS = [
    { id: 'scary', name: 'Scary stuff', desc: 'Witches, ghosts, Halloween, monsters',
      rule: 'Nothing involving witches, spells, Halloween, ghosts, zombies, demons or anything made to scare young children.',
      words: ['halloween', 'witch', 'witchcraft', 'ghost', 'zombie', 'demon', 'spooky', 'trick or treat'] },
    { id: 'pranks', name: 'Pranks & meanness', desc: 'Prank videos, bullying, “rage” clips',
      rule: 'No prank videos, bullying, or people being mean to or humiliating others.',
      words: ['prank', 'pranked', 'bully', 'rage'] },
    { id: 'violence', name: 'Violence', desc: 'Fighting, weapons, injuries',
      rule: 'No fighting, weapons, blood or people getting hurt.',
      words: ['fight', 'gun', 'shooting', 'blood', 'gore'] },
    { id: 'unboxing', name: 'Toy unboxing', desc: 'Unboxing, haul and surprise egg videos',
      rule: 'No toy unboxing, haul or surprise egg videos.',
      words: ['unboxing', 'surprise egg', 'haul', 'blind bag'] },
    { id: 'rude', name: 'Rude language', desc: 'Swearing and crude jokes',
      rule: 'No swearing, rude words or crude jokes.',
      words: ['swearing', 'uncensored', 'explicit'] },
    { id: 'gaming', name: 'Gaming videos', desc: 'Let’s plays and game streams',
      rule: 'No video game playthroughs, let’s plays or game streams.',
      words: ['gameplay', 'let’s play', 'walkthrough', 'speedrun'] },
  ];
  const ENDING = 'Ordinary fairy tales and silly cartoons are fine.';

  function welcome() {
    app.innerHTML = `
      <div class="welcome">
        <div class="welcome-in">
          <div class="welcome-logo">${LOGO}</div>
          <h1>YouTube, minus the stuff you’ve said no to.</h1>
          <ul class="welcome-points">
            <li>${icon('play')}<span><b>Normal YouTube.</b> Your child sees the YouTube they know.</span></li>
            <li>${icon('filter')}<span><b>Your rules.</b> Say what to hide in plain words. SafeTube checks every video.</span></li>
            <li>${icon('lock')}<span><b>Locked with a PIN.</b> Only you can change what’s allowed.</span></li>
          </ul>
          <button class="btn btn-sun" data-go>Set it up ${icon('next')}</button>
          <div class="welcome-note">Takes about a minute.</div>
        </div>
      </div>`;
    app.querySelector('[data-go]').addEventListener('click', () => createPin(setupWhat, { setup: true, back: null }));
  }

  // The last setup screen: how to get back here, since the way in is deliberately hidden.
  function allSet() {
    app.innerHTML = `
      <div class="welcome">
        <div class="welcome-in">
          <div class="done-badge">${icon('check')}</div>
          <h1>You’re all set</h1>
          <p class="welcome-lead">To get back to these settings, hold the YouTube logo for three seconds.</p>
          <div class="demo" aria-hidden="true">
            <div class="demo-bar">
              <span class="demo-logo"><i class="demo-play"></i>YouTube<i class="demo-ring"></i></span>
              <span class="demo-icons"><i></i><i></i></span>
            </div>
            <div class="demo-tiles"><i></i><i></i><i></i></div>
          </div>
          <p class="welcome-note">You can also long-press the SafeTube app icon and choose <b>Parent settings</b>.</p>
          <button class="btn btn-sun" data-go>Start watching ${icon('play')}</button>
        </div>
      </div>`;
    app.querySelector('[data-go]').addEventListener('click', () => B.finishSetup());
  }

  function setupSide(step) {
    const steps = ['Create a parent PIN', 'Choose what to hide', 'Time and extras', S.settings.allowedContent ? 'Add a smart checker (required)' : 'Add a smart checker (optional)'];
    return `
      <div class="setup-side">
        <div class="logo">${LOGO}<span>SafeTube</span></div>
        <div class="tagline">YouTube, minus the stuff you’ve said no to.</div>
        <ol class="steps">
          ${steps.map((s, i) => {
            const cls = i + 1 < step ? 'done' : i + 1 === step ? 'now' : '';
            const n = i + 1 < step ? icon('check') : i + 1;
            return `<li class="${cls}"><span class="n">${n}</span>${s}</li>`;
          }).join('')}
        </ol>
        <div class="foot">You can change any of this later.</div>
      </div>`;
  }

  function setupWhat() {
    ensureSettings();
    const chosen = new Set(['scary']);
    let edited = false;
    app.innerHTML = `
      <div class="setup">
        ${setupSide(2)}
        <div class="setup-main">
          <div class="eyebrow">Step 2 of 4</div>
          <h1>What should SafeTube hide?</h1>
          <p class="lead">Tap everything that applies. Each one adds rules and keywords for you.</p>
          <div class="presets">
            ${PRESETS.map((p) => `
              <button class="preset" data-p="${p.id}" aria-pressed="false">
                <b>${esc(p.name)}</b><span>${esc(p.desc)}</span><i class="tick">${icon('check')}</i>
              </button>`).join('')}
          </div>
          <label class="field-label" for="own">Or say it in your own words</label>
          <textarea id="own" class="textarea"></textarea>
          ${contentControls()}
          <h2>How strict</h2>${strictnessControl()}
          <div class="setup-actions">
            <span></span>
            <button class="btn btn-primary" data-next>Continue ${icon('next')}</button>
          </div>
        </div>
      </div>`;
    wireContent(app);
    wireStrictness(app);
    const own = app.querySelector('#own');
    const sync = () => {
      app.querySelectorAll('.preset').forEach((b) => b.setAttribute('aria-pressed', String(chosen.has(b.dataset.p))));
      if (!edited) {
        const picked = PRESETS.filter((p) => chosen.has(p.id));
        own.value = picked.length ? picked.map((p) => p.rule).join(' ') + ' ' + ENDING : '';
      }
    };
    app.querySelector('.presets').addEventListener('click', (e) => {
      const b = e.target.closest('.preset');
      if (!b) return;
      if (chosen.has(b.dataset.p)) chosen.delete(b.dataset.p); else chosen.add(b.dataset.p);
      sync();
    });
    own.addEventListener('input', () => { edited = true; });
    app.querySelector('[data-next]').addEventListener('click', () => {
      if (app.querySelector('#contentMode').value === 'only' && !app.querySelector('#allowedContent').value.trim()) {
        app.querySelector('#content-error').textContent = 'Describe the content to show before continuing.';
        app.querySelector('#allowedContent').focus(); return;
      }
      S.settings.rules = own.value.trim();
      const words = new Set();
      PRESETS.filter((p) => chosen.has(p.id)).forEach((p) => p.words.forEach((w) => words.add(w)));
      S.settings.keywords = [...words];
      save();
      setupExtras();
    });
    sync();
  }

  function setupExtras() {
    app.innerHTML = `
      <div class="setup">
        ${setupSide(3)}
        <div class="setup-main">
          <div class="eyebrow">Step 3 of 4</div>
          <h1>Time and extras</h1>
          <p class="lead">Sensible choices are already set. Change anything you like.</p>
          <div class="extras">
            <section class="card">${screenTimeControls()}</section>
            <section class="card">${extraSwitches()}</section>
          </div>
          <div class="setup-actions">
            <button class="btn btn-ghost" data-back>${icon('back')} Back</button>
            <button class="btn btn-primary" data-next>Continue ${icon('next')}</button>
          </div>
        </div>
      </div>`;
    wireSwitches(app, true);
    wireScreenTime(app, true);
    app.querySelector('[data-back]').addEventListener('click', setupWhat);
    app.querySelector('[data-next]').addEventListener('click', () => { save(); setupChecker(); });
  }

  function setupChecker() {
    if (S.settings.provider.type === 'NONE') S.settings.provider.type = 'OPENROUTER_DECISIONS';
    app.innerHTML = `
      <div class="setup">
        ${setupSide(4)}
        <div class="setup-main">
          <div class="eyebrow">Step 4 of 4 · ${S.settings.allowedContent ? 'required for Only show' : 'optional'}</div>
          <h1>Add a smart checker</h1>
          <p class="lead">An AI reads each new video’s title and channel and hides the ones that break your rules. Keywords and blocks already work without it.</p>
          <p class="note" id="checker-error" role="alert"></p><div id="checker-form"></div>
          <div class="setup-actions">
            <button class="btn btn-ghost" data-skip>Skip for now</button>
            <button class="btn btn-primary" data-done>Finish ${icon('next')}</button>
          </div>
        </div>
      </div>`;
    app.querySelector('#checker-form').insertAdjacentHTML('afterend', backupSection());
    checkerForm(app.querySelector('#checker-form'), { compact: true });
    backupControls(app);
    const finish = (keepChecker) => {
      const configured = (p) => p && (p.type === 'OPENAI_COMPATIBLE' ? !!(p.model && p.baseUrl) : p.type === 'CLEF' ? !!(p.apiKey && p.accountId) : ['OPENROUTER_DECISIONS', 'JEV', 'ANTHROPIC'].includes(p.type) && !!p.apiKey);
      if (S.settings.allowedContent && !((keepChecker && configured(S.settings.provider)) || configured(S.settings.fallbackProvider))) {
        app.querySelector('#checker-error').textContent = 'Only show needs a configured smart checker. Enter the primary or backup credentials before finishing.';
        const input = app.querySelector('#checker-form [data-field="apiKey"]');
        if (input) input.focus(); return;
      }
      if (!keepChecker) S.settings.provider.type = 'NONE';
      save();
      allSet();
    };
    app.querySelector('[data-skip]').addEventListener('click', () => finish(false));
    app.querySelector('[data-done]').addEventListener('click', () => finish(true));
  }

  // ------------------------------------------------------------------ settings shell

  const PAGES = [
    ['overview', 'Overview', 'grid'],
    ['rules', 'What to hide', 'filter'],
    ['lists', 'Videos & channels', 'ban'],
    ['time', 'Time & extras', 'clock'],
    ['checker', 'Smart checker', 'spark'],
    ['settings', 'Settings', 'gear'],
  ];

  function shell() {
    ensureSettings();
    reloadLists();
    app.innerHTML = `
      <div class="shell">
        <nav class="nav" aria-label="Parent settings">
          <div class="logo">${LOGO}<span>SafeTube</span></div>
          ${PAGES.map(([id, label, ic]) => `
            <button class="nav-item" data-page="${id}" ${id === page ? 'aria-current="page"' : ''}>${icon(ic)}${label}</button>`).join('')}
          <button class="btn btn-dark" data-close>${icon('play')} Back to videos</button>
        </nav>
        <main class="main" id="main"></main>
      </div>`;
    app.querySelector('.nav').addEventListener('click', (e) => {
      const b = e.target.closest('[data-page]');
      if (b) { page = b.dataset.page; shell(); window.scrollTo(0, 0); }
    });
    app.querySelector('[data-close]').addEventListener('click', () => B.close());
    const main = app.querySelector('#main');
    ({ overview, rules, lists, time, checker, settings })[page](main);
  }

  const PROVIDERS = {
    NONE: { name: 'Off', desc: 'Keywords and blocks only' },
    OPENROUTER_DECISIONS: { name: 'Clef Flash via OpenRouter', desc: 'Paste your OpenRouter key. Requires OpenRouter credit.', badge: 'Recommended', key: 'OpenRouter API key', link: 'https://openrouter.ai/keys' },
    CLEF: { name: 'Direct Cloudflare', desc: 'Cloudflare account and API token · limited free daily allowance',
      key: 'Cloudflare API token', link: 'https://dash.cloudflare.com/profile/api-tokens' },
    JEV: { name: 'OpenRouter / Jev (existing)', desc: 'Your existing Decisions model · requires OpenRouter credit', key: 'OpenRouter API key', link: 'https://openrouter.ai/keys' },
    ANTHROPIC: { name: 'Claude', desc: 'Anthropic · can look at thumbnails too', key: 'Anthropic API key',
      link: 'https://console.anthropic.com/settings/keys' },
    OPENAI_COMPATIBLE: { name: 'Something else', desc: 'Any OpenAI-compatible service', key: 'API key' },
  };

  function reasonTag(reason) {
    const r = reason || '';
    if (r.startsWith('Keyword:')) return `<span class="tag keyword">Keyword “${esc(r.slice(8).trim())}”</span>`;
    if (r.startsWith('Blocked channel')) return '<span class="tag channel">Channel blocked</span>';
    if (r === 'Set by parent') return '<span class="tag parent">Hidden by hand</span>';
    if (r === 'Set by child') return '<span class="tag child">Hidden by your child</span>';
    return '<span class="tag ai">Matches your rules</span>';
  }

  function videoRow(v, action, label, extraTag) {
    const src = thumb(v.id);
    return `
      <div class="row">
        <button class="video-preview" data-details="${esc(v.id)}" aria-label="View details for ${esc(v.title || v.id)}">${src ? `<img class="thumb" src="${src}" alt="">` : '<span class="thumb"></span>'}</button>
        <button class="t video-preview" data-details="${esc(v.id)}"><b>${esc(v.title || v.id)}</b><small>${esc(v.channel || '')}</small></button>
        ${extraTag || ''}
        <button class="btn btn-ghost btn-small" data-${action}="${esc(v.id)}" data-title="${esc(v.title || '')}">${label}</button>
      </div>`;
  }

  async function videoDetails(id) {
    const known = [...(L.hidden || []), ...(L.history || []), ...(L.blocked || []), ...(L.allowed || [])].find((v) => v.id === id) || { id };
    const dialog = document.createElement('dialog');
    dialog.className = 'video-dialog';
    dialog.innerHTML = '<p>Loading video details…</p><button class="btn" data-close>Close</button>';
    dialog.setAttribute('role', 'dialog'); dialog.setAttribute('aria-modal', 'true'); dialog.setAttribute('aria-label', 'Video details');
    const priorFocus = document.activeElement;
    const restore = () => { app.style.pointerEvents = ''; app.removeAttribute('aria-hidden'); if (priorFocus && priorFocus.isConnected) priorFocus.focus(); };
    app.style.pointerEvents = 'none'; app.setAttribute('aria-hidden', 'true');
    const close = () => { if (typeof dialog.close === 'function') dialog.close(); else dialog.remove(); restore(); };
    document.body.appendChild(dialog);
    if (typeof dialog.showModal === 'function') dialog.showModal();
    else { dialog.setAttribute('open', ''); dialog.classList.add('legacy-dialog'); }
    dialog.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') { close(); return; }
      if (e.key !== 'Tab') return;
      const buttons = dialog.querySelectorAll('button'); const first = buttons[0]; const last = buttons[buttons.length - 1];
      if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
      else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
    });
    dialog.querySelector('[data-close]').focus();
    dialog.addEventListener('close', () => { dialog.remove(); restore(); });
    dialog.querySelector('[data-close]').onclick = () => close();
    const result = await call('lookup', { id });
    if (!dialog.isConnected) return;
    const v = { ...known, ...result, title: result.title || known.title, channel: result.channel || known.channel };
    dialog.innerHTML = `<div class="card-head"><h2>Video details</h2><button class="btn btn-ghost" data-close>Close</button></div>
      <img class="detail-thumb" src="${thumb(id)}" alt=""><h3>${esc(v.title || id)}</h3><p>${esc(v.channel || 'Channel unavailable')}</p>
      <h3>Why this decision?</h3><p class="detail-copy">${esc(v.reason || 'No stored explanation is available for this video.')}</p>
      <p class="note">${v.source ? 'Source: ' + esc(v.source) + '. ' : ''}${v.verdict ? 'Decision: ' + esc(v.verdict) + '. ' : ''}${typeof v.score === 'number' ? 'Rule-match score: ' + esc(v.score) + '. ' : ''}${typeof v.threshold === 'number' ? 'Hide threshold: ' + esc(v.threshold) + '.' : ''}</p>
      <p class="note">The score estimates whether this video matches your hide rules. A lower hide threshold is stricter. This is a metadata assessment, not a review of everything spoken or shown.</p><h3>Available description</h3><p class="detail-copy">${esc(v.description || 'The full video description is not available here. Open the video to review it before changing your rules.')}</p>
      ${v.error ? `<p class="note">${esc(v.error)}</p>` : ''}
      <div class="dialog-actions"><button class="btn btn-primary" data-watch>View on YouTube</button><button class="btn btn-ghost" data-allow-detail>Allow video</button><button class="btn btn-ghost" data-edit>Edit rules</button></div>`;
    dialog.querySelector('[data-close]').onclick = () => close();
    dialog.querySelector('[data-close]').focus();
    dialog.querySelector('[data-watch]').onclick = () => B.openLink('https://www.youtube.com/watch?v=' + encodeURIComponent(id));
    dialog.querySelector('[data-allow-detail]').onclick = () => { B.allow(id, v.title || id); close(); toast('Allowed.'); shell(); };
    dialog.querySelector('[data-edit]').onclick = () => { close(); page = 'rules'; shell(); };
  }

  // Allow / Undo / Unblock buttons inside any list.
  function wireRows(root) {
    root.addEventListener('click', (e) => {
      const details = e.target.closest('[data-details]');
      if (details) { videoDetails(details.dataset.details); return; }
      const allow = e.target.closest('[data-allow]');
      const undo = e.target.closest('[data-undo]');
      const unblock = e.target.closest('[data-unblock]');
      if (allow) { B.allow(allow.dataset.allow, allow.dataset.title); toast('Allowed. It’ll show up again.'); }
      else if (undo) { B.undo(undo.dataset.undo); toast('Back to normal checking.'); }
      else if (unblock) {
        B.unblockChannel(unblock.dataset.unblock);
        toast('Channel unblocked.');
      } else return;
      shell();
    });
  }

  function overview(main) {
    const p = S.settings.provider;
    const kw = S.settings.keywords;
    main.innerHTML = `
      <div class="page-head">
        <div><h1>Overview</h1><div class="sub">Here’s what SafeTube did today.</div></div>
        <span class="status-pill">Protection is on</span>
      </div>
      ${L.stats.timeUp ? `
        <section class="card banner">
          <div class="card-head"><div><h2>${L.stats.timeUp === 'bedtime' ? 'It’s bedtime on the tablet' : 'Today’s viewing time is used up'}</h2>
            <p class="note">YouTube is closed for now. Give a little more time if you want.</p></div>
            <button class="btn btn-sun btn-small" data-more-time>Add 15 minutes</button></div>
        </section>` : ''}
      <div id="now"></div>
      <div class="stats">
        <div class="stat"><div class="k">Videos checked today</div><div class="v">${L.stats.checkedToday || 0}</div></div>
        <div class="stat"><div class="k">Hidden today</div><div class="v">${L.stats.hiddenToday || 0}</div></div>
        <div class="stat"><div class="k">Watched today</div><div class="v">${minutesText(L.stats.watchedMin || 0)}${L.stats.limitMin ? `<small> of ${minutesText(L.stats.limitMin)}</small>` : ''}</div></div>
        <div class="stat"><div class="k">Smart checker</div><div class="v small">${esc(PROVIDERS[p.type].name)}${p.type === 'CLEF' ? ' · free' : ''}</div></div>
      </div>
      <div class="cols">
        <section class="card grow" id="recent">
          <div class="card-head"><h2>Recently hidden</h2>${L.hidden.length > 6 ? '<a href="#" data-go="lists">See all</a>' : ''}</div>
          <div class="rows">
            ${L.hidden.length ? L.hidden.slice(0, 6).map((v) => videoRow(v, 'allow', 'Allow', reasonTag(v.reason))).join('')
              : '<div class="empty">Nothing hidden yet. Videos SafeTube hides will show up here.</div>'}
          </div>
        </section>
        <div class="side">
          <section class="card dark">
            <div class="card-head"><h2>Your rules</h2><a href="#" data-go="rules" style="color: var(--sun)">Edit</a></div>
            <p class="note" style="color: var(--night-text)">${S.settings.rules ? `“${esc(S.settings.rules.length > 140 ? S.settings.rules.slice(0, 140) + '…' : S.settings.rules)}”` : 'No rules written yet.'}</p>
            <div class="chips">
              ${kw.slice(0, 4).map((k) => `<span class="chip">${esc(k)}</span>`).join('')}
              ${kw.length > 4 ? `<span class="chip">+ ${kw.length - 4} more</span>` : ''}
            </div>
          </section>
          <section class="card">
            <h2>How strict</h2>
            ${strictnessControl()}
          </section>
        </div>
      </div>`;
    wireRows(main.querySelector('#recent'));
    wireStrictness(main);
    const more = main.querySelector('[data-more-time]');
    if (more) more.addEventListener('click', () => { B.addTime(15); toast('15 more minutes added.'); shell(); });
    main.querySelectorAll('[data-go]').forEach((a) => a.addEventListener('click', (e) => {
      e.preventDefault();
      page = a.dataset.go;
      shell();
    }));
    nowPlaying(main.querySelector('#now'));
  }

  const STRICT_TEXT = {
    STRICT: 'Hides anything the checker isn’t fairly sure is fine.',
    BALANCED: 'Hides videos that probably break your rules.',
    PERMISSIVE: 'Hides only clear matches.',
  };
  function strictnessControl() {
    const s = S.settings.strictness;
    return `
      <div class="seg" role="radiogroup" aria-label="How strict">
        ${[['STRICT', 'Strict'], ['BALANCED', 'Balanced'], ['PERMISSIVE', 'Relaxed']].map(([v, l]) =>
          `<button role="radio" data-strict="${v}" aria-checked="${s === v}">${l}</button>`).join('')}
      </div>
      <p class="note" data-strict-text>${STRICT_TEXT[s]}</p>`;
  }
  function wireStrictness(root) {
    root.querySelectorAll('[data-strict]').forEach((b) => b.addEventListener('click', () => {
      S.settings.strictness = b.dataset.strict;
      root.querySelectorAll('[data-strict]').forEach((x) => x.setAttribute('aria-checked', String(x === b)));
      root.querySelector('[data-strict-text]').textContent = STRICT_TEXT[b.dataset.strict];
      save('Saved');
    }));
  }

  // When settings were opened from a video or channel, offer to hide it in one tap.
  function nowPlaying(box) {
    const c = S.current;
    if (!c || (!c.videoId && !c.channelRef)) return;
    if (!c.videoId) {
      box.innerHTML = `
        <div class="now-playing">
          <span class="thumb"></span>
          <div class="t"><small>Open right now</small><b>${esc(c.channelRef)}</b></div>
          <button class="btn btn-sun btn-small" data-hide-channel>${icon('ban')} Hide this channel</button>
        </div>`;
      box.querySelector('[data-hide-channel]').addEventListener('click', () => {
        B.blockChannel(JSON.stringify([c.channelRef]));
        S.current = null;
        toast('Channel hidden.');
        shell();
      });
      return;
    }
    box.innerHTML = `
      <div class="now-playing">
        <img src="${thumb(c.videoId)}" alt="">
        <div class="t"><small>Playing right now</small><b data-title>Looking it up…</b></div>
        <button class="btn btn-sun btn-small" data-hide-video>${icon('eyeoff')} Hide this video</button>
        <button class="btn btn-ghost btn-small hidden" data-hide-channel></button>
      </div>`;
    let info = null;
    call('lookup', { id: c.videoId }).then((r) => {
      info = r;
      box.querySelector('[data-title]').textContent = r.title ? `${r.title}${r.channel ? ' · ' + r.channel : ''}` : 'This video';
      if (r.channel) {
        const b = box.querySelector('[data-hide-channel]');
        b.textContent = `Hide everything from ${r.channel}`;
        b.classList.remove('hidden');
      }
    });
    box.querySelector('[data-hide-video]').addEventListener('click', () => {
      B.blockVideo(c.videoId, (info && info.title) || '');
      S.current = null;
      toast('That video is now always hidden.');
      shell();
    });
    box.querySelector('[data-hide-channel]').addEventListener('click', () => {
      B.blockChannel(JSON.stringify([info.channelId, info.channel].filter(Boolean)));
      S.current = null;
      toast(`${info.channel} is now hidden.`);
      shell();
    });
  }

  function rules(main) {
    ensureSettings();
    main.innerHTML = `
      <div class="page-head">
        <div><h1>What to hide</h1><div class="sub">Changes apply straight away, even to videos already on screen.</div></div>
      </div>
      <div class="cols">
        <div class="grow">
          <section class="card">
            <label for="rules"><h2>In your own words</h2></label>
            <p class="note">The smart checker reads every video against this.</p>
            <textarea id="rules" class="textarea">${esc(S.settings.rules)}</textarea>
            ${contentControls()}
          </section>
          <section class="card">
            <div class="card-head">
              <h2>Keywords</h2>
              <button class="btn btn-dark btn-small" data-suggest>${icon('spark')} Suggest more from my rules</button>
            </div>
            <p class="note">Any video whose title mentions one of these is hidden instantly, no AI needed.</p>
            <div class="chips" id="chips"></div>
            <div id="suggest"></div>
          </section>
        </div>
        <div class="side">
          <section class="card"><h2>Ready-made hide rules</h2><p class="note">Add a preset at any time. Your existing rules and keywords stay in place.</p>
            <div class="presets">${PRESETS.map((p) => `<button class="preset" data-add-preset="${p.id}"><b>${esc(p.name)}</b><span>${esc(p.desc)}</span></button>`).join('')}</div>
          </section>
          <section class="card">
            <h2>More options</h2>
            <p class="note">Ads, autoplay, Shorts, comments, Restricted Mode, screen time and bedtime are under Time &amp; extras.</p>
            <button class="btn btn-ghost btn-small" data-go="time">${icon('clock')} Time &amp; extras</button>
          </section>
        </div>
      </div>`;
    wireContent(main);
    main.querySelectorAll('[data-add-preset]').forEach((b) => b.addEventListener('click', () => {
      const p = PRESETS.find((p) => p.id === b.dataset.addPreset);
      if (!S.settings.rules.includes(p.rule)) S.settings.rules = [S.settings.rules, p.rule].filter(Boolean).join(' ');
      S.settings.keywords = [...new Set([...S.settings.keywords, ...p.words])];
      save('Preset added'); rules(main);
    }));
    const ta = main.querySelector('#rules');
    ta.addEventListener('change', () => { S.settings.rules = ta.value.trim(); save('Rules saved'); });
    drawChips(main.querySelector('#chips'));
    main.querySelector('[data-go="time"]').addEventListener('click', () => { page = 'time'; shell(); });
    main.querySelector('[data-suggest]').addEventListener('click', () => suggest(main.querySelector('#suggest'), ta));
  }

  function drawChips(box) {
    box.innerHTML = S.settings.keywords.map((k, i) => `
      <span class="chip">${esc(k)}<button aria-label="Remove ${esc(k)}" data-rm="${i}">${icon('x')}</button></span>`).join('') +
      '<input class="chip-input" placeholder="Add a keyword" aria-label="Add a keyword" enterkeyhint="done">';
    box.onclick = (e) => {
      const b = e.target.closest('[data-rm]');
      if (!b) return;
      S.settings.keywords.splice(Number(b.dataset.rm), 1);
      save('Keyword removed');
      drawChips(box);
    };
    const input = box.querySelector('.chip-input');
    const add = () => {
      const words = input.value.split(',').map((w) => w.trim().toLowerCase()).filter(Boolean);
      if (!words.length) return;
      words.forEach((w) => { if (!S.settings.keywords.includes(w)) S.settings.keywords.push(w); });
      save('Keyword added');
      drawChips(box);
      box.querySelector('.chip-input').focus();
    };
    input.addEventListener('keydown', (e) => { if (e.key === 'Enter' || e.key === ',') { e.preventDefault(); add(); } });
    input.addEventListener('change', add);
  }

  function suggest(box, ta) {
    const h = S.settings.helper;
    if (h.type === 'NONE' || !h.apiKey) {
      box.innerHTML = `
        <div class="suggest">
          <div class="msg">The keyword helper isn’t set up yet. Gemini’s free tier works well and takes a minute.</div>
          <div class="chips"><button class="btn btn-sun btn-small" data-go-checker>Set it up</button></div>
        </div>`;
      box.querySelector('[data-go-checker]').addEventListener('click', () => { page = 'checker'; shell(); });
      return;
    }
    S.settings.rules = ta.value.trim();
    box.innerHTML = '<div class="suggest"><div class="msg">Thinking of keywords…</div></div>';
    call('suggest', { rules: S.settings.rules, keywords: S.settings.keywords, helper: h }).then((r) => {
      if (r.error) {
        box.innerHTML = `<div class="suggest"><div class="msg">That didn’t work: ${esc(r.error)}</div></div>`;
        return;
      }
      let words = (r.keywords || []).filter((w) => !S.settings.keywords.includes(w));
      if (!words.length) {
        box.innerHTML = '<div class="suggest"><div class="msg">No new keywords to add. Your list already covers it.</div></div>';
        return;
      }
      const draw = () => {
        if (!words.length) { box.innerHTML = ''; return; }
        box.innerHTML = `
          <div class="suggest">
            <div class="card-head"><b>Suggested for you</b><button class="btn btn-sun btn-small" data-all>Add all ${words.length}</button></div>
            <div class="chips">${words.map((w, i) => `<button class="add" data-add="${i}">+ ${esc(w)}</button>`).join('')}</div>
          </div>`;
      };
      box.onclick = (e) => {
        const all = e.target.closest('[data-all]');
        const one = e.target.closest('[data-add]');
        if (!all && !one) return;
        const picked = all ? words : [words[Number(one.dataset.add)]];
        picked.forEach((w) => S.settings.keywords.push(w));
        words = all ? [] : words.filter((w) => !picked.includes(w));
        save(all ? 'Keywords added' : 'Keyword added');
        drawChips(document.getElementById('chips'));
        draw();
      };
      draw();
    });
  }

  // ------------------------------------------------------------------ time & extras

  const minutesText = (m) => m >= 60 ? `${Math.floor(m / 60)}h${m % 60 ? ` ${m % 60}m` : ''}` : `${m}m`;
  function whenText(ts) {
    const d = new Date(ts);
    const today = new Date();
    const time = d.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
    return d.toDateString() === today.toDateString() ? `Today ${time}` : `${d.toLocaleDateString([], { weekday: 'short' })} ${time}`;
  }

  const LIMITS = [[0, 'Off'], [30, '30m'], [60, '1h'], [90, '1½h'], [120, '2h'], [180, '3h']];

  /** Daily limit and bedtime, used in setup and on the Time & extras page. */
  function screenTimeControls() {
    const st = S.settings;
    return `
      <div class="field-label">Daily limit</div>
      <div class="seg seg-wide" role="radiogroup" aria-label="Daily limit">
        ${LIMITS.map(([v, l]) => `<button role="radio" data-limit="${v}" aria-checked="${st.dailyLimitMin === v}">${l}</button>`).join('')}
      </div>
      ${switchRow('bedtimeOn', 'Bedtime', 'YouTube closes between these times.')}
      <div class="times" data-times ${st.bedtimeOn ? '' : 'hidden'}>
        <label>From <input class="input" type="time" data-time="bedtimeStart" value="${esc(st.bedtimeStart)}"></label>
        <label>Until <input class="input" type="time" data-time="bedtimeEnd" value="${esc(st.bedtimeEnd)}"></label>
      </div>`;
  }
  function wireScreenTime(root, quiet) {
    root.querySelectorAll('[data-limit]').forEach((b) => b.addEventListener('click', () => {
      S.settings.dailyLimitMin = Number(b.dataset.limit);
      root.querySelectorAll('[data-limit]').forEach((x) => x.setAttribute('aria-checked', String(x === b)));
      save(quiet ? '' : 'Saved');
    }));
    root.querySelectorAll('[data-time]').forEach((i) => i.addEventListener('change', () => {
      if (!/^\d\d:\d\d$/.test(i.value)) return;
      S.settings[i.dataset.time] = i.value;
      save(quiet ? '' : 'Saved');
    }));
  }

  /** Every on/off option, in one place. */
  function extraSwitches() {
    return `
      ${switchRow('blockAds', 'Block ads', 'Skips video ads and hides ads between videos.')}
      ${switchRow('noAutoplay', 'No autoplay', 'When a video ends, YouTube doesn’t start another one by itself.')}
      ${switchRow('hideShorts', 'Hide Shorts', 'Short videos open in the normal player instead.')}
      ${switchRow('hideComments', 'Hide comments')}
      ${switchRow('restrictedMode', 'YouTube Restricted Mode', 'YouTube’s own filter as an extra safety net. Only on this device, never on your Google account.')}
      ${switchRow('keepHistory', 'Keep a watch history', 'A list for you of what was watched. Stays on this device.')}
      ${switchRow('kidsCanBlock', 'My child can hide videos and channels', 'They hold a video to hide it. Only you can bring it back.')}`;
  }

  function time(main) {
    const used = L.stats.watchedMin || 0;
    const limit = L.stats.limitMin || 0;
    main.innerHTML = `
      <div class="page-head">
        <div><h1>Time &amp; extras</h1><div class="sub">Screen time and how YouTube behaves.</div></div>
      </div>
      <div class="cols">
        <div class="grow">
          <section class="card">
            <div class="card-head"><div><h2>Screen time</h2>
              <p class="note">Watched today: <b>${minutesText(used)}</b>${limit ? ` of ${minutesText(limit)}` : ''}.</p></div>
              ${S.settings.dailyLimitMin || S.settings.bedtimeOn ? '<button class="btn btn-ghost btn-small" data-more-time>Add 15 minutes</button>' : ''}</div>
            ${screenTimeControls()}
          </section>
        </div>
        <div class="side">
          <section class="card">
            <h2>On YouTube</h2>
            ${extraSwitches()}
          </section>
        </div>
      </div>`;
    wireSwitches(main);
    wireScreenTime(main);
    const more = main.querySelector('[data-more-time]');
    if (more) more.addEventListener('click', () => { B.addTime(15); toast('15 more minutes added.'); shell(); });
  }

  function switchRow(key, label, sub) {
    return `
      <div class="switch-row">
        <span>${label}${sub ? `<small>${sub}</small>` : ''}</span>
        <button class="switch" role="switch" data-switch="${key}" aria-checked="${!!S.settings[key]}" aria-label="${label}"></button>
      </div>`;
  }
  function wireSwitches(root, quiet) {
    root.querySelectorAll('[data-switch]').forEach((b) => b.addEventListener('click', () => {
      const k = b.dataset.switch;
      S.settings[k] = !S.settings[k];
      b.setAttribute('aria-checked', String(S.settings[k]));
      const times = root.querySelector('[data-times]');
      if (k === 'bedtimeOn' && times) times.hidden = !S.settings[k];
      save(quiet ? '' : 'Saved');
    }));
  }

  function lists(main) {
    main.innerHTML = `
      <div class="page-head">
        <div><h1>Videos &amp; channels</h1><div class="sub">Everything you or your child chose by hand.</div></div>
      </div>
      <div class="cols">
        <div class="grow">
          <section class="card">
            <h2>Hide a video or channel</h2>
            <p class="note">Paste a video link to hide just that video, or a channel link, @handle or channel name to hide everything from it.</p>
            <form class="paste" data-paste>
              <input class="input" name="link" placeholder="youtube.com/watch?v=…  or  @channel" aria-label="Video or channel link" autocomplete="off">
              <button class="btn btn-primary btn-small" type="submit">Hide it</button>
            </form>
          </section>
          <section class="card" data-list>
            <h2>Always hidden</h2>
            <div class="rows">
              ${L.blocked.length ? L.blocked.map((v) => videoRow(v, 'undo', 'Unhide',
                v.by === 'child' ? '<span class="tag child">By your child</span>' : '<span class="tag parent">By you</span>')).join('')
                : '<div class="empty">Nothing yet. Hold a video on the YouTube screen to hide it.</div>'}
            </div>
          </section>
          ${S.settings.keepHistory ? `
          <section class="card" id="history">
            <div class="card-head"><h2>Watched recently</h2>${L.history && L.history.length ? '<button class="btn btn-ghost btn-small" data-clear-history>Clear</button>' : ''}</div>
            <div class="rows">
              ${L.history && L.history.length ? L.history.slice(0, 30).map((v) => `
                <div class="row">
                  ${thumb(v.id) ? `<img class="thumb" src="${thumb(v.id)}" alt="">` : '<span class="thumb"></span>'}
                  <div class="t"><b>${esc(v.title || v.id)}</b><small>${esc(v.channel || '')} · ${esc(whenText(v.ts))}</small></div>
                  <button class="btn btn-ghost btn-small" data-details="${esc(v.id)}">Details</button><button class="btn btn-ghost btn-small" data-hist-video="${esc(v.id)}" data-title="${esc(v.title || '')}">Hide</button>
                  ${v.channelId || v.channel ? `<button class="btn btn-ghost btn-small" data-hist-channel="${esc(v.channelId || '')}" data-name="${esc(v.channel || '')}">Hide channel</button>` : ''}
                </div>`).join('') : '<div class="empty">Videos your child watches will show up here.</div>'}
            </div>
          </section>` : ''}
          <section class="card" data-list>
            <h2>Recently hidden by your rules</h2>
            <div class="rows">
              ${L.hidden.length ? L.hidden.map((v) => videoRow(v, 'allow', 'Allow', reasonTag(v.reason))).join('')
                : '<div class="empty">Nothing hidden yet.</div>'}
            </div>
          </section>
        </div>
        <div class="side">
          <section class="card" data-list>
            <h2>Blocked channels</h2>
            <div class="rows">
              ${L.channels.length ? L.channels.map((c) => `
                <div class="row"><span class="avatar">${icon('user')}</span>
                  <div class="t"><b>${esc(c)}</b><small>Every video hidden</small></div>
                  <button class="btn btn-ghost btn-small" data-unblock="${esc(c)}">Unblock</button></div>`).join('')
                : '<div class="empty">No channels blocked.</div>'}
            </div>
          </section>
          <section class="card" data-list>
            <h2>Always allowed</h2>
            <div class="rows">
              ${L.allowed.length ? L.allowed.map((v) => videoRow(v, 'undo', 'Undo')).join('')
                : '<div class="empty">Videos you allow show up here.</div>'}
            </div>
          </section>
        </div>
      </div>`;
    main.querySelectorAll('[data-list]').forEach(wireRows);
    const hist = main.querySelector('#history');
    if (hist) hist.addEventListener('click', (e) => {
      const details = e.target.closest('[data-details]');
      if (details) { videoDetails(details.dataset.details); return; }
      const v = e.target.closest('[data-hist-video]');
      const c = e.target.closest('[data-hist-channel]');
      const clear = e.target.closest('[data-clear-history]');
      if (v) { B.blockVideo(v.dataset.histVideo, v.dataset.title); toast('That video is now always hidden.'); }
      else if (c) {
        B.blockChannel(JSON.stringify([c.dataset.histChannel, c.dataset.name].filter(Boolean)));
        toast(`${c.dataset.name || 'That channel'} is now hidden.`);
      } else if (clear) { B.clearHistory(); toast('History cleared.'); }
      else return;
      shell();
    });
    main.querySelector('[data-paste]').addEventListener('submit', (e) => {
      e.preventDefault();
      const text = e.target.link.value.trim();
      if (!text) return;
      call('blockLink', { text }).then((r) => {
        toast(r.message);
        if (r.ok) shell();
      });
    });
  }

  // The provider picker and its fields; used on the checker page and in setup.
  function checkerForm(box, { compact, fallback = false } = {}) {
    ensureSettings();
    const p = fallback ? S.settings.fallbackProvider : S.settings.provider;
    const recommended = 'OPENROUTER_DECISIONS';
    const types = ['CLEF', 'ANTHROPIC', 'OPENAI_COMPATIBLE', ...(p.type === 'JEV' ? ['JEV'] : []), ...(!compact ? ['NONE'] : [])];
    const info = PROVIDERS[p.type];
    const modelHint = S.defaults.models[p.type] || (p.type === recommended ? 'cloudflare/clef-flash' : 'e.g. gpt-4o-mini');
    const chat = p.type === 'ANTHROPIC' || p.type === 'OPENAI_COMPATIBLE';
    const providerButton = (t) => `<button class="provider" role="radio" data-type="${t}" aria-checked="${p.type === t}">
      <b>${PROVIDERS[t].name}${PROVIDERS[t].badge ? ` <span class="badge">${PROVIDERS[t].badge}</span>` : ''}</b>
      <span>${fallback && t === 'NONE' ? 'No backup checker' : PROVIDERS[t].desc}</span></button>`;
    box.innerHTML = `
      <div role="radiogroup" aria-label="${fallback ? 'Backup checker' : 'Video checker'}">
        <div class="recommended-provider">${providerButton(recommended)}</div>
        <details class="more" data-other-providers ${p.type !== recommended ? 'open' : ''}>
          <summary>Other checkers</summary><div class="providers">${types.map(providerButton).join('')}</div>
        </details>
      </div>
      ${p.type === 'NONE' ? '' : `
      <div class="fields">
        ${p.type === 'CLEF' ? field('accountId', 'Cloudflare account ID', p.accountId) : ''}
        ${p.type === 'OPENAI_COMPATIBLE' ? field('baseUrl', 'Base URL', p.baseUrl, 'https://openrouter.ai/api/v1') : ''}
        ${field('apiKey', info.key, p.apiKey, '', 'password')}
        ${p.type === 'OPENAI_COMPATIBLE' ? field('model', 'Model', p.model, 'e.g. gpt-4o-mini') : ''}
      </div>
      ${info.link ? `<p class="note"><a href="#" data-link="${info.link}">Where do I find ${p.type === 'CLEF' ? 'these' : 'this'}?</a></p>` : ''}
      ${p.type === 'OPENAI_COMPATIBLE' && !chat ? '' : `
      <details class="more" data-model-options ${p.model ? 'open' : ''}>
        <summary>${p.type === recommended ? 'Advanced: model' : 'More options'}</summary>
        <div class="fields">
          ${p.type !== 'OPENAI_COMPATIBLE' ? field('model', p.type === recommended ? 'OpenRouter Decisions model' : 'Model', p.model, modelHint) : ''}
          ${p.type === recommended ? `<p class="note wide">Recommended: ${esc(modelHint)}. Use an OpenRouter Decisions model ID here. Chat models are not supported by this checker.</p><button class="btn btn-ghost btn-small" data-reset-model>Reset to recommended model</button>` : ''}
          ${chat ? `<label class="check-row wide"><input type="checkbox" data-thumbs ${p.sendThumbnails ? 'checked' : ''}> Also look at thumbnails (catches more, costs a little more)</label>` : ''}
        </div>
      </details>`}
      ${fallback ? '<p class="note">Tried automatically when the primary checker fails.</p>' : `
      <div class="test-box">
        <button class="btn btn-dark btn-small" data-test>Test it</button>
        <div class="test-out" data-out><span class="muted">Checks a Halloween song and a counting song against your rules.</span></div>
      </div>`}`}`;
    box.querySelectorAll('[data-type]').forEach((b) => b.addEventListener('click', () => {
      if (p.type !== b.dataset.type) {
        p.type = b.dataset.type;
        p.model = ''; // a model name for one provider means nothing to another
        save();
      }
      checkerForm(box, { compact, fallback });
    }));
    box.querySelectorAll('[data-field]').forEach((input) => input.addEventListener('change', () => {
      p[input.dataset.field] = input.value.trim();
      save('Saved');
    }));
    const resetModel = box.querySelector('[data-reset-model]');
    if (resetModel) resetModel.addEventListener('click', () => { p.model = ''; save('Recommended model restored'); checkerForm(box, { compact, fallback }); });
    const thumbs = box.querySelector('[data-thumbs]');
    if (thumbs) thumbs.addEventListener('change', () => { p.sendThumbnails = thumbs.checked; save('Saved'); });
    const link = box.querySelector('[data-link]');
    if (link) link.addEventListener('click', (e) => { e.preventDefault(); B.openLink(link.dataset.link); });
    const test = box.querySelector('[data-test]');
    if (test) test.addEventListener('click', () => {
      box.querySelectorAll('[data-field]').forEach((i) => { p[i.dataset.field] = i.value.trim(); });
      save();
      const out = box.querySelector('[data-out]');
      out.innerHTML = '<span class="muted">Checking…</span>';
      test.disabled = true;
      call('testAi', {}).then((r) => {
        test.disabled = false;
        if (r.error) { out.innerHTML = `<span>That didn’t work: ${esc(r.error)}</span>`; return; }
        out.innerHTML = r.results.map((x) => `
          <div><span class="tag ${x.hidden ? 'child' : 'ai'}">${x.hidden ? 'Hidden' : 'Shown'}</span>${esc(x.title)}</div>`).join('');
      });
    });
  }

  function field(key, label, value, placeholder, type) {
    return `<label>${esc(label)}<input class="input" data-field="${key}" type="${type || 'text'}" value="${esc(value || '')}"
      placeholder="${esc(placeholder || '')}" autocomplete="off" autocapitalize="off" spellcheck="false"></label>`;
  }

  function backupSection() {
    return '<section class="card"><h2>Safety</h2><div id="safety-control"></div><details class="more" data-backup><summary>Optional backup checker</summary><p class="note">Try a separate provider or model if the primary checker fails or runs out.</p><div id="backup-form"></div></details></section>';
  }

  function backupControls(root) {
    ensureSettings();
    root.querySelector('[data-backup]').open = S.settings.fallbackProvider.type !== 'NONE';
    checkerForm(root.querySelector('#backup-form'), { fallback: true });
    root.querySelector('#safety-control').innerHTML = switchRow('blockOnAiFailure', 'Pause YouTube if checks run out', 'Recommended. If both checkers fail, all of YouTube is locked, including previously allowed videos, until a successful retry or a parent changes settings. If off, unchecked videos still stay hidden.');
    wireSwitches(root.querySelector('#safety-control'));
  }

  function checker(main) {
    main.innerHTML = `
      <div class="page-head">
        <div><h1>Smart checker</h1><div class="sub">An AI reads each video’s title and channel against your rules. Optional: keywords and blocks work without it.</div></div>
      </div>
      <div class="cols">
        <div class="grow"><section class="card"><h2>Video checker</h2><div id="form"></div></section>${backupSection()}</div>
        <div class="side">
          <section class="card" id="helper"></section>
          <section class="card dark" style="display: flex; gap: 14px">
            <span style="color: var(--sun)">${icon('shield')}</span>
            <p class="note" style="margin: 0">Your keys stay on this tablet. The checker receives video titles, channel names, available page text and thumbnails when enabled. Saved checks are reused while your rules stay the same.</p>
          </section>
        </div>
      </div>`;
    checkerForm(main.querySelector('#form'));
    backupControls(main);
    helperCard(main.querySelector('#helper'), false);
  }

  function helperCard(box, editing) {
    const h = S.settings.helper;
    const ready = h.type !== 'NONE' && h.apiKey;
    const isGemini = h.type === 'OPENAI_COMPATIBLE' && h.baseUrl === S.defaults.geminiBaseUrl;
    const name = h.type === 'ANTHROPIC' ? 'Claude' : isGemini ? 'Gemini' : 'Custom';
    box.innerHTML = `
      <h2>Keyword helper</h2>
      <p class="note">Turns your rules into a long keyword list. It only ever sees your rules.</p>
      ${ready && !editing ? `
        <div class="helper-card">
          <span class="ic">${icon('spark')}</span>
          <div style="flex: 1"><b>${name}</b><div class="muted" style="font-size: 13px">${isGemini ? 'Free tier' : esc(h.model || '')}</div></div>
          <span class="connected">Connected</span>
        </div>
        <div class="chips"><button class="btn btn-ghost btn-small" data-edit>Change</button></div>` : !editing ? `
        <div class="chips"><button class="btn btn-sun btn-small" data-gemini>${icon('spark')} Use Gemini (free)</button>
          <button class="btn btn-ghost btn-small" data-edit>Use another</button></div>` : `
        <div class="fields">
          ${isGemini ? '' : `<label class="wide">Service
            <select class="input" data-htype>
              <option value="OPENAI_COMPATIBLE" ${h.type !== 'ANTHROPIC' ? 'selected' : ''}>OpenAI-compatible</option>
              <option value="ANTHROPIC" ${h.type === 'ANTHROPIC' ? 'selected' : ''}>Claude (Anthropic)</option>
            </select></label>`}
          <label class="wide">${isGemini ? 'Gemini API key' : 'API key'}<input class="input" type="password" data-h="apiKey" value="${esc(h.apiKey)}" autocomplete="off"></label>
          ${isGemini || h.type === 'ANTHROPIC' ? '' : `<label class="wide">Base URL<input class="input" data-h="baseUrl" value="${esc(h.baseUrl)}" autocomplete="off" autocapitalize="off"></label>`}
          ${isGemini ? '' : `<label class="wide">Model<input class="input" data-h="model" value="${esc(h.model)}" autocomplete="off" autocapitalize="off"></label>`}
        </div>
        ${isGemini ? '<p class="note"><a href="#" data-link="https://aistudio.google.com/apikey">Get a free Gemini key</a></p>' : ''}
        <div class="chips"><button class="btn btn-primary btn-small" data-done>Done</button></div>`}`;
    const on = (sel, fn) => { const el = box.querySelector(sel); if (el) el.addEventListener('click', fn); };
    on('[data-edit]', () => {
      if (h.type === 'NONE') { h.type = 'OPENAI_COMPATIBLE'; h.baseUrl = ''; h.model = ''; }
      helperCard(box, true);
    });
    on('[data-gemini]', () => {
      h.type = 'OPENAI_COMPATIBLE';
      h.baseUrl = S.defaults.geminiBaseUrl;
      h.model = S.defaults.geminiModel;
      helperCard(box, true);
    });
    on('[data-done]', () => {
      box.querySelectorAll('[data-h]').forEach((i) => { h[i.dataset.h] = i.value.trim(); });
      if (!h.apiKey) h.type = 'NONE';
      save(h.apiKey ? 'Keyword helper saved' : '');
      helperCard(box, false);
    });
    const sel = box.querySelector('[data-htype]');
    if (sel) sel.addEventListener('change', () => {
      box.querySelectorAll('[data-h]').forEach((i) => { h[i.dataset.h] = i.value.trim(); });
      h.type = sel.value;
      helperCard(box, true);
    });
    const link = box.querySelector('[data-link]');
    if (link) link.addEventListener('click', (e) => { e.preventDefault(); B.openLink(link.dataset.link); });
  }

  function settings(main) {
    main.innerHTML = `
      <div class="page-head"><div><h1>Settings</h1></div></div>
      <div class="cols">
        <div class="grow">
          <section class="card">
            <div class="card-head"><div><h2>Parent PIN</h2><p class="note">Needed to open these settings.</p></div>
              <button class="btn btn-ghost btn-small" data-pin>Change PIN</button></div>
          </section>
          <section class="card">
            <div class="card-head"><div><h2>Check every video again</h2>
              <p class="note">Forgets the smart checker’s past answers. Videos you hid or allowed by hand stay as they are.</p></div>
              <button class="btn btn-danger btn-small" data-forget>Check again</button></div>
          </section>
        </div>
        <div class="side">
          <section class="card">
            <h2>About</h2>
            <p class="note">SafeTube ${esc(S.version)}. Open source under the MIT licence.</p>
            <p class="note">Fonts: DM Sans and Bricolage Grotesque, SIL Open Font License.</p>
          </section>
        </div>
      </div>`;
    main.querySelector('[data-pin]').addEventListener('click', () => {
      createPin(() => { toast('New PIN saved'); shell(); }, { setup: false, back: { label: 'Cancel', go: shell } });
    });
    main.querySelector('[data-forget]').addEventListener('click', () => {
      B.forgetAi();
      toast('Every video will be checked again.');
      shell();
    });
  }

  // ------------------------------------------------------------------ start

  if (S.mode === 'setup') {
    welcome();
  } else if (S.mode === 'block') {
    const b = S.block;
    unlock(() => B.confirmBlock(), {
      title: b.kind === 'channel' ? 'Hide this channel?' : 'Hide this video?',
      text: b.kind === 'channel' ? `Everything from ${b.channel || 'this channel'} will be hidden. Enter your PIN to confirm.`
        : `“${b.title || 'This video'}” will be hidden. Enter your PIN to confirm.`,
      backLabel: 'Cancel',
    });
  } else {
    unlock(shell, { title: 'Grown-ups only', text: 'Enter your PIN to change what SafeTube hides.' });
  }
})();
