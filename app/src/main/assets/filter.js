/*
 * SafeTube page filter. Runs inside YouTube pages in the app's WebView.
 *
 * The one rule that matters: a video nobody has approved is never visible.
 * CSS hides every video card and every link to a video until the app says
 * "allow" for that video id. Blocked videos are removed from the layout.
 *
 * Talks to the app through window.SafeTubeAndroid:
 *   config()      -> JSON string of page options
 *   submit(json)  <- batch of videos the page needs decisions for
 * and the app answers by calling window.__safetube.apply({id: "allow"|"block"|"unknown"}).
 *
 * YouTube changes its markup often. If videos stop appearing, the selectors
 * below are the place to look; the link-level rule still keeps unapproved
 * videos hidden even when the card selectors miss.
 */
(function () {
  'use strict';
  if (window.__safetube) return;

  var bridge = window.SafeTubeAndroid || null;
  var cfg = { hideShorts: true, hideComments: true, debug: false };
  try { if (bridge) Object.assign(cfg, JSON.parse(bridge.config())); } catch (e) {}

  // One element per video in lists, grids, search results, playlists and "up next".
  var CARD_SELECTORS = [
    // m.youtube.com (phones and most tablets)
    'ytm-video-with-context-renderer', 'ytm-compact-video-renderer', 'ytm-video-card-renderer',
    'ytm-playlist-video-renderer', 'ytm-playlist-panel-video-renderer', 'ytm-reel-item-renderer',
    'ytm-shorts-lockup-view-model', 'ytm-shorts-lockup-view-model-v2', 'ytm-media-item',
    'ytm-compact-autoplay-renderer', 'ytm-rich-item-renderer',
    // www.youtube.com (desktop layout)
    'ytd-rich-item-renderer', 'ytd-video-renderer', 'ytd-compact-video-renderer', 'ytd-grid-video-renderer',
    'ytd-playlist-video-renderer', 'ytd-playlist-panel-video-renderer', 'ytd-reel-item-renderer',
    'ytd-movie-renderer', 'ytd-endscreen-element-renderer',
    // newer shared components
    'yt-lockup-view-model', 'ytm-shorts-lockup-view-model',
    // end-of-video suggestions inside the player
    '.ytp-videowall-still', '.ytp-ce-element', '.ytp-autonav-endscreen-upnext-container'
  ];
  var CARD_SEL = CARD_SELECTORS.join(',');
  var VIDEO_LINK_SEL = 'a[href*="/watch?"], a[href*="/watch/"], a[href*="/shorts/"], a[href*="youtu.be/"], a[href*="/live/"]';

  var PLAYER_SEL = [
    'video', '#player', '#movie_player', 'ytm-player', '.player-container', '#player-container-id',
    'ytd-player', '#full-bleed-container', '.html5-video-player'
  ].join(',');

  var SHORTS_SEL = [
    'ytm-reel-shelf-renderer', 'ytd-reel-shelf-renderer', 'ytm-shorts-lockup-view-model',
    'ytm-shorts-lockup-view-model-v2', 'ytd-rich-shelf-renderer[is-shorts]',
    'ytm-pivot-bar-item-renderer:has(.pivot-shorts)', 'ytd-guide-entry-renderer:has(a[title="Shorts"])',
    'ytd-mini-guide-entry-renderer:has(a[title="Shorts"])', 'ytm-chip-cloud-chip-renderer:has(a[href*="/shorts"])',
    'grid-shelf-view-model:has(ytm-shorts-lockup-view-model)', 'ytd-rich-section-renderer:has(ytd-rich-shelf-renderer[is-shorts])',
    'yt-tab-shape[tab-title="Shorts"]'
  ].join(',');

  var COMMENTS_SEL = [
    'ytm-comments-entry-point-header-renderer', 'ytm-comment-section-renderer', 'ytd-comments', '#comments',
    'ytm-item-section-renderer[section-identifier="comments-entry-point"]',
    'ytd-engagement-panel-section-list-renderer[target-id="engagement-panel-comments-section"]',
    'ytm-engagement-panel-section-list-renderer[panel-target-id="engagement-panel-comments-section"]'
  ].join(',');

  // Ads are never checked, so they are never shown.
  var ADS_SEL = [
    'ytm-promoted-sparkles-web-renderer', 'ytm-promoted-video-renderer', 'ytm-companion-ad-renderer',
    'ytm-ad-slot-renderer', 'ytd-ad-slot-renderer', 'ytd-promoted-sparkles-web-renderer',
    'ytd-in-feed-ad-layout-renderer', 'ytd-banner-promo-renderer', 'ytd-statement-banner-renderer',
    '#masthead-ad', 'ytm-statement-banner-renderer', 'ad-slot-renderer'
  ].join(',');

  var ID_RE = /^[A-Za-z0-9_-]{11}$/;
  var verdicts = Object.create(null);   // id -> "allow" | "block"
  var retryAt = Object.create(null);    // id -> time we may ask again after "unknown"
  var queued = Object.create(null);     // id -> video waiting to be sent
  var inFlight = Object.create(null);   // id -> time sent
  var flushTimer = null;
  var scanScheduled = false;
  var RETRY_MS = 20000;
  var INFLIGHT_TIMEOUT_MS = 45000;

  // ---------------------------------------------------------------- styles

  function css() {
    var notOk = ':not([data-st="allow"])';
    var rules = [];
    CARD_SELECTORS.forEach(function (s) {
      rules.push(s + notOk + '{visibility:hidden!important}');
      rules.push(s + '[data-st="block"]{display:none!important}');
    });
    // Safety net: links to unapproved videos are hidden even outside a known card.
    VIDEO_LINK_SEL.split(', ').forEach(function (s) {
      rules.push(s + notOk + '{visibility:hidden!important}');
    });
    // The player stays hidden (and paused) until the current video is approved.
    PLAYER_SEL.split(',').forEach(function (s) {
      rules.push('html:not([data-st-watch="allow"]) ' + s + '{visibility:hidden!important}');
    });
    rules.push(ADS_SEL + '{display:none!important}');
    if (cfg.hideShorts) rules.push(SHORTS_SEL + '{display:none!important}');
    if (cfg.hideComments) rules.push(COMMENTS_SEL + '{display:none!important}');
    rules.push(
      '#st-overlay{position:fixed;inset:0;z-index:2147483647;display:flex;flex-direction:column;' +
      'align-items:center;justify-content:center;gap:16px;background:#fff;color:#0f0f0f;' +
      'font:16px/1.4 Roboto,Arial,sans-serif;text-align:center;padding:24px}' +
      '#st-overlay button{font:inherit;padding:10px 24px;border-radius:20px;border:0;background:#0f0f0f;color:#fff}' +
      '@media (prefers-color-scheme:dark){#st-overlay{background:#0f0f0f;color:#f1f1f1}#st-overlay button{background:#f1f1f1;color:#0f0f0f}}'
    );
    return rules.join('\n');
  }

  function installStyle() {
    // At document start there may be no <html> element yet; the next scan will try again.
    var parent = document.head || document.documentElement;
    if (!parent || document.getElementById('st-style')) return;
    var style = document.createElement('style');
    style.id = 'st-style';
    style.textContent = css();
    parent.appendChild(style);
  }

  // ---------------------------------------------------------------- ids and metadata

  function idFromUrl(href) {
    if (!href) return null;
    var u;
    try { u = new URL(href, location.href); } catch (e) { return null; }
    var id = null;
    if (/(^|\.)youtu\.be$/.test(u.hostname)) id = u.pathname.slice(1, 12);
    else if (u.pathname === '/watch') id = u.searchParams.get('v');
    else {
      var m = u.pathname.match(/^\/(shorts|watch|live|embed|v)\/([A-Za-z0-9_-]{11})/);
      if (m) id = m[2];
    }
    return id && ID_RE.test(id) ? id : null;
  }

  function text(el) {
    return el ? (el.textContent || '').replace(/\s+/g, ' ').trim() : '';
  }

  var TITLE_SEL = '#video-title, .media-item-headline, .compact-media-item-headline, h3, h4, ' +
    '.yt-lockup-metadata-view-model__title, .ytp-videowall-still-info-title, .ytp-autonav-endscreen-upnext-title, ' +
    '.shortsLockupViewModelHostMetadataTitle, .ytp-ce-video-title';
  var CHANNEL_SEL = '#channel-name, .media-item-byline, .compact-media-item-byline, ytm-badge-and-byline-renderer, ' +
    '.yt-content-metadata-view-model__metadata-row, .ytp-videowall-still-info-author, ytd-channel-name';

  // Links to the uploader's channel, used for "block this channel".
  var CHANNEL_LINK_SEL = 'a[href^="/@"], a[href*="youtube.com/@"], a[href^="/channel/UC"], a[href*="youtube.com/channel/UC"]';

  function describe(card, link, id) {
    var title = text(card.querySelector(TITLE_SEL));
    if (!title && link) title = (link.getAttribute('title') || link.getAttribute('aria-label') || text(link)).trim();
    if (!title) title = (card.getAttribute('aria-label') || '').trim();
    var channel = text(card.querySelector(CHANNEL_SEL));
    var context = text(card).slice(0, 400);
    var channelLink = card.querySelector(CHANNEL_LINK_SEL);
    var channelId = channelLink ? channelLink.getAttribute('href') || '' : '';
    return {
      id: id, title: title.slice(0, 300), channel: channel.slice(0, 120), context: context,
      channelId: channelId.slice(0, 200)
    };
  }

  function cardVideoLink(card) {
    if (card.matches && card.matches(VIDEO_LINK_SEL)) return card;
    return card.querySelector(VIDEO_LINK_SEL);
  }

  // ---------------------------------------------------------------- marking

  function mark(el, id) {
    var v = verdicts[id];
    var state = v === 'allow' ? 'allow' : v === 'block' ? 'block' : 'pending';
    if (el.getAttribute('data-st-id') !== id) el.setAttribute('data-st-id', id);
    if (el.getAttribute('data-st') !== state) el.setAttribute('data-st', state);
    return state;
  }

  function need(video, priority) {
    var id = video.id;
    if (verdicts[id]) return;
    var now = Date.now();
    if (inFlight[id] && now - inFlight[id] < INFLIGHT_TIMEOUT_MS) return;
    if (retryAt[id] && now < retryAt[id]) return;
    var prev = queued[id];
    if (!prev || (!prev.title && video.title)) queued[id] = video;
    scheduleFlush(priority ? 0 : 80);
  }

  function scheduleFlush(delay) {
    if (flushTimer !== null) {
      if (delay > 0) return;
      clearTimeout(flushTimer);
    }
    flushTimer = setTimeout(flush, delay);
  }

  function flush() {
    flushTimer = null;
    var batch = Object.keys(queued).map(function (k) { return queued[k]; });
    queued = Object.create(null);
    if (!batch.length) return;
    var now = Date.now();
    batch.forEach(function (v) { inFlight[v.id] = now; });
    if (bridge) {
      try { bridge.submit(JSON.stringify(batch)); } catch (e) { log('submit failed ' + e); }
    } else if (window.__safetubeTestBridge) {
      window.__safetubeTestBridge(batch);
    }
  }

  // ---------------------------------------------------------------- scanning

  function scan() {
    scanScheduled = false;
    if (!document.documentElement) return;
    installStyle();

    var cards = document.querySelectorAll(CARD_SEL);
    for (var i = 0; i < cards.length; i++) {
      var card = cards[i];
      var link = cardVideoLink(card);
      var id = link ? idFromUrl(link.getAttribute('href')) : null;
      if (!id) {
        // Not a video (channel, playlist header, ad slot...). Shown as YouTube made it,
        // but any video links inside it are still filtered individually below.
        if (card.getAttribute('data-st') !== 'allow') {
          card.setAttribute('data-st', 'allow');
          card.removeAttribute('data-st-id');
        }
        continue;
      }
      if (mark(card, id) === 'pending') need(describe(card, link, id), false);
    }

    var links = document.querySelectorAll(VIDEO_LINK_SEL);
    for (var j = 0; j < links.length; j++) {
      var a = links[j];
      var lid = idFromUrl(a.getAttribute('href'));
      if (!lid) { if (a.getAttribute('data-st') !== 'allow') a.setAttribute('data-st', 'allow'); continue; }
      if (mark(a, lid) === 'pending') {
        var host = a.closest(CARD_SEL) || a;
        need(describe(host, a, lid), false);
      }
    }

    checkWatchPage();
  }

  function scheduleScan() {
    if (scanScheduled) return;
    scanScheduled = true;
    (window.requestAnimationFrame || setTimeout)(scan, 16);
  }

  // ---------------------------------------------------------------- the video being watched

  var overlayFor = null;

  function currentWatchId() {
    var p = location.pathname;
    if (p === '/watch' || p.indexOf('/shorts/') === 0 || p.indexOf('/live/') === 0) return idFromUrl(location.href);
    return null;
  }

  function checkWatchPage() {
    var html = document.documentElement;
    if (cfg.hideShorts && location.pathname.indexOf('/shorts') === 0) {
      var sid = currentWatchId();
      // Shorts are hidden in this mode; open the same video in the normal player instead.
      location.replace(sid ? '/watch?v=' + sid : '/');
      return;
    }
    var id = currentWatchId();
    if (!id) {
      html.setAttribute('data-st-watch', 'allow');
      removeOverlay();
      return;
    }
    var v = verdicts[id];
    if (v === 'allow') {
      if (html.getAttribute('data-st-watch') !== 'allow') html.setAttribute('data-st-watch', 'allow');
      removeOverlay();
      return;
    }
    html.setAttribute('data-st-watch', v === 'block' ? 'block' : 'pending');
    pauseAll();
    if (v === 'block') showBlocked(id);
    else if (retryAt[id] && Date.now() < retryAt[id]) {
      showOverlay(id, "This video couldn't be checked right now. Try again in a moment.", true);
    } else {
      showOverlay(id, 'Checking this video…', false);
      // The title on the watch page may still belong to the previous video, so let the app look it up.
      need({ id: id, title: '', channel: '', context: '', channelId: '' }, true);
    }
  }

  function pauseAll() {
    var vids = document.querySelectorAll('video');
    for (var i = 0; i < vids.length; i++) {
      try { vids[i].pause(); vids[i].muted = true; } catch (e) {}
    }
  }

  // Catch playback that starts before (or between) scans.
  document.addEventListener('play', function (e) {
    var html = document.documentElement;
    if (html && html.getAttribute('data-st-watch') !== 'allow' && e.target && e.target.pause) {
      e.target.pause();
    }
  }, true);

  function showOverlay(id, message, withButton) {
    var el = document.getElementById('st-overlay');
    if (el && overlayFor === id + message) return;
    if (!el) {
      el = document.createElement('div');
      el.id = 'st-overlay';
      (document.body || document.documentElement).appendChild(el);
    }
    overlayFor = id + message;
    el.textContent = '';
    var p = document.createElement('div');
    p.textContent = message;
    el.appendChild(p);
    if (withButton) {
      var b = document.createElement('button');
      b.textContent = 'Go back';
      b.addEventListener('click', goBack);
      el.appendChild(b);
    }
  }

  function showBlocked(id) {
    showOverlay(id, "This video isn't available.", true);
  }

  function removeOverlay() {
    var el = document.getElementById('st-overlay');
    if (el) el.remove();
    overlayFor = null;
  }

  function goBack() {
    removeOverlay();
    if (history.length > 1) history.back();
    else location.replace('/');
  }

  // Stop taps on blocked or unchecked videos before YouTube's own handlers run.
  document.addEventListener('click', function (e) {
    var a = e.target && e.target.closest ? e.target.closest(VIDEO_LINK_SEL) : null;
    if (!a) return;
    var id = idFromUrl(a.getAttribute('href'));
    if (id && verdicts[id] !== 'allow') {
      e.preventDefault();
      e.stopPropagation();
    }
  }, true);

  // ---------------------------------------------------------------- API used by the app

  function apply(result) {
    if (typeof result === 'string') result = JSON.parse(result);
    var now = Date.now();
    Object.keys(result).forEach(function (id) {
      var v = result[id];
      delete inFlight[id];
      if (v === 'allow' || v === 'block') {
        verdicts[id] = v;
        delete retryAt[id];
      } else {
        retryAt[id] = now + RETRY_MS;
      }
    });
    scan();
  }

  /** Forget everything (rules changed) and re-check what's on screen. */
  function reset(newCfg) {
    verdicts = Object.create(null);
    retryAt = Object.create(null);
    inFlight = Object.create(null);
    queued = Object.create(null);
    if (newCfg) {
      cfg = Object.assign(cfg, typeof newCfg === 'string' ? JSON.parse(newCfg) : newCfg);
      var old = document.getElementById('st-style');
      if (old) old.remove();
    }
    var marked = document.querySelectorAll('[data-st]');
    for (var i = 0; i < marked.length; i++) marked[i].removeAttribute('data-st');
    scan();
  }

  function log(msg) {
    if (cfg.debug) try { console.log('[safetube] ' + msg); } catch (e) {}
  }

  window.__safetube = { apply: apply, reset: reset, scan: scan, idFromUrl: idFromUrl, _verdicts: function () { return verdicts; } };

  // ---------------------------------------------------------------- start

  installStyle();
  new MutationObserver(scheduleScan).observe(document, {
    childList: true, subtree: true, attributes: true, attributeFilter: ['href']
  });
  ['yt-navigate-start', 'yt-navigate-finish', 'state-navigatestart', 'state-navigateend', 'popstate', 'hashchange']
    .forEach(function (ev) { window.addEventListener(ev, scheduleScan, true); });
  ['pushState', 'replaceState'].forEach(function (fn) {
    var orig = history[fn];
    history[fn] = function () {
      var r = orig.apply(this, arguments);
      // Hide the player synchronously so nothing plays during the gap before the next scan.
      var id = currentWatchId();
      if (id && verdicts[id] !== 'allow' && document.documentElement) {
        document.documentElement.setAttribute('data-st-watch', 'pending');
      }
      scheduleScan();
      return r;
    };
  });
  // Belt and braces: YouTube sometimes swaps content without mutations we can see.
  setInterval(scan, 1000);
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', scan);
  scan();
})();
