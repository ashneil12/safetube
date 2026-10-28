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
 *   block(json)   <- the hold menu asked to hide a video or channel
 *   openParent()  <- the YouTube logo was held for three seconds
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
  var cfg = { hideShorts: true, hideComments: true, blockMenu: true, kidsCanBlock: true, blockAds: true, noAutoplay: true, keepHistory: true, debug: false };
  try { if (bridge) Object.assign(cfg, JSON.parse(bridge.config())); } catch (e) {}

  // ---------------------------------------------------------------- ads

  // Video ads come from the player's own data, so they're removed there before the player
  // reads it: the first page load (ytInitialPlayerResponse), later videos (fetch to the
  // youtubei API) and anything parsed from text. Ads that still slip through are skipped.
  var AD_KEYS = ['adPlacements', 'adSlots', 'playerAds', 'adBreakHeartbeatParams'];

  function pruneAds(o) {
    if (!o || typeof o !== 'object') return o;
    var found = false;
    [o, o.playerResponse].forEach(function (t) {
      if (!t || typeof t !== 'object') return;
      for (var i = 0; i < AD_KEYS.length; i++) {
        if (AD_KEYS[i] in t) { delete t[AD_KEYS[i]]; found = true; }
      }
    });
    if (found) log('removed ads from player data');
    return o;
  }

  function installAdBlock() {
    var parse = JSON.parse;
    JSON.parse = function () {
      var r = parse.apply(this, arguments);
      return r && typeof r === 'object' ? pruneAds(r) : r;
    };

    var initial = pruneAds(window.ytInitialPlayerResponse);
    try {
      Object.defineProperty(window, 'ytInitialPlayerResponse', {
        configurable: true,
        get: function () { return initial; },
        set: function (v) { initial = pruneAds(v); }
      });
    } catch (e) {}

    var origFetch = window.fetch;
    if (origFetch) {
      window.fetch = function (input) {
        var url = typeof input === 'string' ? input : (input && input.url) || '';
        var p = origFetch.apply(this, arguments);
        if (!/\/youtubei\/v1\/(player|next|reel)/.test(url)) return p;
        return p.then(function (res) {
          if (!res.ok) return res;
          return res.clone().text().then(function (text) {
            var data;
            try { data = parse(text); } catch (e) { return res; }
            pruneAds(data);
            var headers = new Headers(res.headers);
            headers.delete('content-length');
            headers.delete('content-encoding');
            return new Response(JSON.stringify(data), { status: res.status, statusText: res.statusText, headers: headers });
          }, function () { return res; });
        });
      };
    }

    // Belt and braces: if an ad starts anyway, mute it, run it to the end and press Skip.
    var mutedForAd = null;
    setInterval(function () {
      var player = document.querySelector('.html5-video-player');
      var video = player && player.querySelector('video');
      if (!video) return;
      if (player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting')) {
        if (mutedForAd === null) mutedForAd = video.muted;
        video.muted = true;
        if (isFinite(video.duration) && video.duration > 0) video.currentTime = video.duration;
        var skip = player.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button');
        if (skip) skip.click();
      } else if (mutedForAd !== null) {
        video.muted = mutedForAd;
        mutedForAd = null;
      }
    }, 300);
  }
  if (cfg.blockAds) installAdBlock();

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
  // Holding the YouTube logo this long opens the parent settings.
  var PARENT_HOLD_MS = 3000, RING_DELAY_MS = 500;
  var LOGO_SEL = 'ytm-home-logo, .mobile-topbar-header-endpoint, ytd-topbar-logo-renderer, #logo, a[aria-label="YouTube Home"]';
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
    '#masthead-ad', 'ytm-statement-banner-renderer', 'ad-slot-renderer',
    'ytm-promoted-sparkles-text-search-renderer', 'ytd-promoted-sparkles-text-search-renderer',
    'ytd-player-legacy-desktop-watch-ads-renderer', 'ytm-companion-slot', '.ytp-ad-overlay-container',
    'ytd-merch-shelf-renderer', 'ytm-merch-shelf-renderer', 'ytd-enforcement-message-view-model'
  ].join(',');

  // The countdown and the wall of suggestions at the end of a video.
  var AUTOPLAY_SEL = [
    '.ytp-autonav-endscreen-upnext-container', '.ytp-autonav-endscreen-countdown-overlay', '.ytp-endscreen-content',
    '.ytm-autonav-bar', 'ytm-autonav-toggle-button-container', '.ytp-autonav-toggle-button-container'
  ].join(',');

  var ID_RE = /^[A-Za-z0-9_-]{11}$/;
  var aiUnavailable = !!cfg.aiUnavailable;
  var verdicts = Object.create(null);   // id -> "allow" | "block"
  var retryAt = Object.create(null);    // id -> time we may ask again after "unknown"
  var queuedTargets = Object.create(null); // id -> elements that made this request visible
  var queued = Object.create(null);     // id -> video waiting to be sent
  var inFlight = Object.create(null);   // id -> time sent
  var flushTimer = null;
  var scanScheduled = false;
  var viewportScanTimer = null;
  // Check ahead of the scroll: this many screens below and one above. Videos are asked about
  // while the child is still looking at the ones before them, so they appear already checked.
  var PREFETCH_SCREENS_BELOW = 4;
  var PREFETCH_SCREENS_ABOVE = 1;
  var PREFETCH_MARGIN_PX = 240; // sideways, for carousels
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
    rules.push('html[data-st-ai-unavailable] body,html[data-st-ai-unavailable] body *{visibility:hidden!important;pointer-events:none!important}' +
      'html[data-st-ai-unavailable] #st-overlay,html[data-st-ai-unavailable] #st-overlay *{visibility:visible!important;pointer-events:auto!important}');
    rules.push(ADS_SEL + '{display:none!important}');
    if (cfg.hideShorts) rules.push(SHORTS_SEL + '{display:none!important}');
    if (cfg.hideComments) rules.push(COMMENTS_SEL + '{display:none!important}');
    if (cfg.noAutoplay) rules.push(AUTOPLAY_SEL + '{display:none!important}');
    rules.push(
      // SafeTube's few screens copy YouTube's own look so they blend in.
      '#st-overlay{position:fixed;inset:0;z-index:2147483647;display:flex;flex-direction:column;' +
      'align-items:center;justify-content:center;gap:12px;background:#0f0f0f;color:#fff;' +
      'font:14px/1.4 Roboto,Arial,sans-serif;text-align:center;padding:24px}' +
      '#st-overlay svg{width:56px;height:56px;color:#aaa}' +
      '#st-overlay .st-h{font-size:18px;font-weight:500}' +
      '#st-overlay .st-p{color:#aaa}' +
      '#st-overlay button{font:500 14px Roboto,Arial,sans-serif;height:36px;padding:0 16px;border-radius:18px;' +
      'border:0;background:#f1f1f1;color:#0f0f0f;margin-top:8px}' +
      '#st-overlay .st-spin{width:36px;height:36px;border-radius:50%;border:3px solid #3f3f3f;border-top-color:#fff;' +
      'animation:st-spin .9s linear infinite}' +
      '@keyframes st-spin{to{transform:rotate(360deg)}}' +
      // Hold menu, like YouTube's own bottom sheet
      // Grown-up door: a ring that fills while the logo is held
      '#st-ring{position:fixed;z-index:2147483647;width:56px;height:56px;margin:-28px 0 0 -28px;border-radius:50%;' +
      'pointer-events:none;border:3px solid rgba(128,128,128,.35);box-sizing:border-box;opacity:0;transform:scale(.6);' +
      'transition:opacity .2s,transform .2s}' +
      '#st-ring.on{opacity:1;transform:scale(1)}' +
      '#st-ring svg{position:absolute;inset:-3px;width:56px;height:56px;transform:rotate(-90deg)}' +
      '#st-ring circle{fill:none;stroke:#f00;stroke-width:3;stroke-dasharray:163;stroke-dashoffset:163}' +
      '#st-ring.on circle{transition:stroke-dashoffset ' + (PARENT_HOLD_MS - RING_DELAY_MS) + 'ms linear;stroke-dashoffset:0}' +
      '#st-sheet-bg{position:fixed;inset:0;z-index:2147483646;background:rgba(0,0,0,.6)}' +
      '#st-sheet{position:fixed;left:0;right:0;bottom:0;z-index:2147483647;background:#fff;color:#0f0f0f;' +
      'border-radius:12px 12px 0 0;padding:8px 0;font:16px/1.4 Roboto,Arial,sans-serif;' +
      'max-width:560px;margin:0 auto;box-sizing:border-box}' +
      '#st-sheet .st-handle{width:40px;height:4px;border-radius:2px;background:#ccc;margin:4px auto 8px}' +
      '#st-sheet button{display:flex;align-items:center;gap:24px;width:100%;text-align:left;font:inherit;' +
      'min-height:48px;padding:0 24px;border:0;background:none;color:inherit;box-sizing:border-box}' +
      '#st-sheet button:active{background:rgba(0,0,0,.1)}' +
      '#st-sheet button svg{width:24px;height:24px;flex:none}' +
      '#st-sheet button.st-cancel{justify-content:center;margin-top:4px;border-top:1px solid rgba(0,0,0,.1)}' +
      '@media (prefers-color-scheme:dark){#st-sheet{background:#212121;color:#f1f1f1}' +
      '#st-sheet button:active{background:rgba(255,255,255,.1)}#st-sheet button.st-cancel{border-top-color:rgba(255,255,255,.1)}}'
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

  // visibility:hidden preserves layout, so measure after marking unknown content hidden.
  // display:none/zero-sized content cannot be seen and does not need a speculative check.
  function nearScreen(el) {
    if (!el || !el.isConnected || !el.getBoundingClientRect) return false;
    var rect = el.getBoundingClientRect();
    var width = window.innerWidth || document.documentElement.clientWidth;
    var height = window.innerHeight || document.documentElement.clientHeight;
    return rect.width > 0 && rect.height > 0 &&
      rect.bottom >= -PREFETCH_SCREENS_ABOVE * height && rect.top <= height * (1 + PREFETCH_SCREENS_BELOW) &&
      rect.right >= -PREFETCH_MARGIN_PX && rect.left <= width + PREFETCH_MARGIN_PX;
  }

  function onScreen(el) {
    if (!el || !el.getBoundingClientRect) return false;
    var r = el.getBoundingClientRect();
    return r.bottom > 0 && r.top < (window.innerHeight || document.documentElement.clientHeight);
  }

  function need(video, priority, target) {
    if (!priority && !nearScreen(target)) return;
    var id = video.id;
    if (verdicts[id] && !aiUnavailable) return;
    var now = Date.now();
    if (inFlight[id] && now - inFlight[id] < INFLIGHT_TIMEOUT_MS) return;
    if (retryAt[id] && now < retryAt[id]) return;
    if (target) {
      var targets = queuedTargets[id] || (queuedTargets[id] = []);
      if (targets.indexOf(target) < 0) targets.push(target);
    }
    var prev = queued[id];
    if (!prev || (!prev.title && video.title)) queued[id] = video;
    // Cards on screen go out almost at once; the ones ahead can wait a moment to travel together.
    scheduleFlush(priority ? 0 : (onScreen(target) ? 25 : 90));
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
    // A fast scroll or recycled card can remove demand during the batching delay.
    var watchId = currentWatchId();
    var batch = Object.keys(queued).filter(function (id) {
      return id === watchId || (queuedTargets[id] || []).some(function (target) {
        return target.getAttribute('data-st-id') === id && nearScreen(target);
      });
    }).map(function (id) { return queued[id]; });
    queued = Object.create(null);
    queuedTargets = Object.create(null);
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

  // Elements the page hasn't looked at yet. Cheap enough to check on every change.
  var NEW_CARD_SEL = CARD_SELECTORS.map(function (s) { return s + ':not([data-st])'; }).join(',');
  var NEW_LINK_SEL = VIDEO_LINK_SEL.split(',').map(function (s) { return s.trim() + ':not([data-st])'; }).join(',');

  /**
   * full: look at every video on the page (after answers arrive, and once a second).
   * Otherwise only at elements that are new or that YouTube reused for another video, which
   * keeps scrolling smooth on slow tablets. Either way, anything unchecked stays hidden by CSS.
   */
  function scan(full) {
    scanScheduled = false;
    if (!document.documentElement) return;
    installStyle();
    if (aiUnavailable) { lockPage(); }
    if (full !== false) full = true;

    var roots = full || !dirty ? [document] : dirty;
    dirty = [];
    for (var r = 0; r < roots.length; r++) {
      var root = roots[r];
      if (root !== document && !root.isConnected) continue;
      if (root !== document && root.matches(CARD_SEL)) scanCard(root);
      var cards = root.querySelectorAll(full ? CARD_SEL : NEW_CARD_SEL);
      for (var i = 0; i < cards.length; i++) scanCard(cards[i]);
      if (root !== document && root.matches(VIDEO_LINK_SEL)) scanLink(root);
      var links = root.querySelectorAll(full ? VIDEO_LINK_SEL : NEW_LINK_SEL);
      for (var j = 0; j < links.length; j++) scanLink(links[j]);
    }
    checkWatchPage();
  }

  function scanCard(card) {
    var link = cardVideoLink(card);
    var id = link ? idFromUrl(link.getAttribute('href')) : null;
    if (!id) {
      // Not a video (channel, playlist header, ad slot...). Shown as YouTube made it,
      // but any video links inside it are still filtered individually.
      if (card.getAttribute('data-st') !== 'allow') {
        card.setAttribute('data-st', 'allow');
        card.removeAttribute('data-st-id');
      }
      return;
    }
    if (mark(card, id) === 'pending') {
      var target = nearScreen(card) ? card : link;
      // display:contents wrappers have no own box. Mark the measurable link now,
      // so flush can verify its video id even before the link pass runs.
      if (target === link) mark(link, id);
      need(describe(card, link, id), false, target);
    }
  }

  function scanLink(a) {
    var lid = idFromUrl(a.getAttribute('href'));
    if (!lid) { if (a.getAttribute('data-st') !== 'allow') a.setAttribute('data-st', 'allow'); return; }
    if (mark(a, lid) === 'pending') need(describe(a.closest(CARD_SEL) || a, a, lid), false, a);
  }

  // Elements added or changed since the last scan; null means "look everywhere".
  var dirty = [];

  function scheduleScan() {
    if (scanScheduled) return;
    scanScheduled = true;
    (window.requestAnimationFrame || setTimeout)(function () { scan(false); }, 16);
  }

  // Scroll events also bubble from YouTube's nested lists. Coalesce bursts into at
  // most one full demand scan every 100ms, including on continuous scrolling.
  function scheduleViewportScan() {
    if (viewportScanTimer !== null) return;
    viewportScanTimer = setTimeout(function () {
      viewportScanTimer = null;
      scan();
    }, 100);
  }

  /**
   * Look only where YouTube changed something. A card reused for a different video (its link
   * changed) is looked at afresh; cards nest, so every card around the link is cleared.
   */
  function onMutations(records) {
    for (var i = 0; i < records.length; i++) {
      var r = records[i];
      if (r.type === 'attributes') {
        var a = r.target;
        if (a.removeAttribute) a.removeAttribute('data-st');
        var top = a;
        for (var card = a.closest && a.closest(CARD_SEL); card; card = card.parentElement && card.parentElement.closest(CARD_SEL)) {
          card.removeAttribute('data-st');
          top = card;
        }
        if (dirty) dirty.push(top);
      } else if (dirty) {
        for (var j = 0; j < r.addedNodes.length; j++) {
          var n = r.addedNodes[j];
          if (n.nodeType !== 1) continue;
          // A new link inside an existing card can change which video that card is.
          var outer = n.parentElement && n.parentElement.closest(CARD_SEL);
          if (outer && n.querySelector && (n.matches(VIDEO_LINK_SEL) || n.querySelector(VIDEO_LINK_SEL))) {
            outer.removeAttribute('data-st');
            dirty.push(outer);
          }
          dirty.push(n);
        }
      }
      if (dirty && dirty.length > 300) dirty = null; // a whole new page: one sweep is cheaper
    }
    scheduleScan();
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
    if (aiUnavailable) {
      lockPage();
      var retryId = currentWatchId();
      if (retryId) need({ id: retryId, title: '', channel: '', context: '', channelId: '' }, true);
      return;
    }
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
      noteWatched(id);
      autoplayOff();
      return;
    }
    html.setAttribute('data-st-watch', v === 'block' ? 'block' : 'pending');
    pauseAll();
    if (v === 'block') showBlocked(id);
    else if (retryAt[id] && Date.now() < retryAt[id]) {
      showOverlay(id, 'error');
    } else {
      showOverlay(id, 'checking');
      // The title on the watch page may still belong to the previous video, so let the app look it up.
      need({ id: id, title: '', channel: '', context: '', channelId: '' }, true);
    }
  }

  // ---------------------------------------------------------------- history and autoplay

  // A video counts as watched after a few seconds on its page; each is reported once.
  var watchTimer = null;
  var watchFor = null;
  var reported = Object.create(null);

  function noteWatched(id) {
    if (!cfg.keepHistory || reported[id] || watchFor === id) return;
    clearTimeout(watchTimer);
    watchFor = id;
    watchTimer = setTimeout(function () {
      watchFor = null;
      if (currentWatchId() !== id || verdicts[id] !== 'allow') return;
      reported[id] = true;
      var info = JSON.stringify(currentVideo() || { id: id });
      if (bridge && bridge.watched) {
        try { bridge.watched(info); } catch (e) { log('watched failed ' + e); }
      } else if (window.__safetubeTestWatched) {
        window.__safetubeTestWatched(JSON.parse(info));
      }
    }, WATCHED_AFTER_MS);
  }
  var WATCHED_AFTER_MS = cfg.watchedAfterMs || 8000;

  // With autoplay off, moving to another video right after one ends, with no tap from the
  // child, is YouTube's autoplay: go back to the video that just finished.
  var lastTouchAt = 0;
  var endedAt = 0;
  var endedId = null;
  ['touchstart', 'mousedown', 'keydown'].forEach(function (ev) {
    document.addEventListener(ev, function () { lastTouchAt = Date.now(); }, { capture: true, passive: true });
  });
  document.addEventListener('ended', function () {
    endedAt = Date.now();
    endedId = currentWatchId();
  }, true);

  function isAutoplayJump() {
    if (!cfg.noAutoplay || !endedAt) return false;
    var now = Date.now();
    var id = currentWatchId();
    var jump = id && id !== endedId && now - endedAt < 30000 && now - lastTouchAt > 2500;
    if (jump || now - endedAt >= 30000) endedAt = 0;
    return jump;
  }

  // Also switch YouTube's own autoplay toggle off, so it doesn't try in the first place.
  function autoplayOff() {
    if (!cfg.noAutoplay) return;
    var on = document.querySelector(
      'button.ytm-autonav-toggle-button-container[aria-pressed="true"], .ytp-autonav-toggle-button[aria-checked="true"]'
    );
    if (on) try { on.click(); } catch (e) {}
  }

  function pauseAll() {
    var vids = document.querySelectorAll('video,audio');
    for (var i = 0; i < vids.length; i++) {
      try { vids[i].pause(); vids[i].muted = true; } catch (e) {}
    }
  }

  // Catch playback that starts before (or between) scans.
  document.addEventListener('play', function (e) {
    var html = document.documentElement;
    if ((aiUnavailable || (html && html.getAttribute('data-st-watch') !== 'allow')) && e.target && e.target.pause) {
      e.target.pause();
    }
  }, true);

  // Small stroke icons, built with DOM calls because YouTube's page forbids innerHTML.
  var ICONS = {
    unavailable: ['M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z', 'M12 8v5', 'M12 16h.01'],
    eyeoff: ['M3 3l18 18', 'M10.6 5.1A10.7 10.7 0 0 1 12 5c6 0 9.5 7 9.5 7a17.6 17.6 0 0 1-3 3.9M6.6 6.6C3.9 8.4 2.5 12 2.5 12S6 19 12 19c1.9 0 3.6-.7 5-1.6', 'M9.9 9.9a3 3 0 0 0 4.2 4.2'],
    ban: ['M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z', 'M5.6 5.6l12.8 12.8'],
    retry: ['M3 12a9 9 0 0 1 15.5-6.2L21 8', 'M21 3v5h-5', 'M21 12a9 9 0 0 1-15.5 6.2L3 16', 'M3 21v-5h5']
  };
  function svgIcon(name) {
    var ns = 'http://www.w3.org/2000/svg';
    var svg = document.createElementNS(ns, 'svg');
    svg.setAttribute('viewBox', '0 0 24 24');
    svg.setAttribute('fill', 'none');
    svg.setAttribute('stroke', 'currentColor');
    svg.setAttribute('stroke-width', '1.6');
    svg.setAttribute('stroke-linecap', 'round');
    svg.setAttribute('stroke-linejoin', 'round');
    svg.setAttribute('aria-hidden', 'true');
    ICONS[name].forEach(function (d) {
      var path = document.createElementNS(ns, 'path');
      path.setAttribute('d', d);
      svg.appendChild(path);
    });
    return svg;
  }
  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text) e.textContent = text;
    return e;
  }

  // kind: 'blocked', 'checking' or 'error'
  function showOverlay(id, kind) {
    var box = document.getElementById('st-overlay');
    if (box && overlayFor === id + kind) return;
    if (!box) {
      box = document.createElement('div');
      box.id = 'st-overlay';
      (document.body || document.documentElement).appendChild(box);
    }
    overlayFor = id + kind;
    box.textContent = '';
    if (kind === 'checking') {
      box.appendChild(el('div', 'st-spin'));
      box.appendChild(el('div', 'st-p', 'Loading…'));
      return;
    }
    box.appendChild(svgIcon(kind === 'error' ? 'retry' : 'unavailable'));
    box.appendChild(el('div', 'st-h', 'Video unavailable'));
    box.appendChild(el('div', 'st-p', kind === 'error' ? "This video couldn't be checked. Try again in a moment."
      : "This video isn't available."));
    var b = el('button', '', 'Go back');
    b.addEventListener('click', goBack);
    box.appendChild(b);
  }

  function showBlocked(id) {
    showOverlay(id, 'blocked');
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

  // ---------------------------------------------------------------- hold to block

  var HOLD_MS = 550;
  var holdTimer = null;
  var holdStart = null;
  var suppressClickUntil = 0;

  function videoUnder(target) {
    if (!target || !target.closest) return null;
    if (target.closest(WATCH_INFO_SEL)) return currentVideo();
    var card = target.closest(CARD_SEL);
    var link = card ? cardVideoLink(card) : target.closest(VIDEO_LINK_SEL);
    var id = link ? idFromUrl(link.getAttribute('href')) : null;
    if (!id) return null;
    return describe(card || link, link, id);
  }

  // The title and channel under the player; holding them hides the video that's playing.
  var WATCH_INFO_SEL = 'ytm-slim-video-metadata-section-renderer, ytm-slim-owner-renderer, .slim-video-information-renderer, ' +
    'ytd-watch-metadata, #owner, #above-the-fold';

  /** The video on the watch page, for the hold menu. */
  function currentVideo() {
    var id = currentWatchId();
    if (!id) return null;
    var owner = document.querySelector(
      'ytm-slim-owner-renderer, #owner, ytd-video-owner-renderer, ytm-video-owner-renderer, .slim-owner-icon-and-title'
    );
    var channelLink = owner && owner.querySelector(CHANNEL_LINK_SEL);
    var title = text(document.querySelector(
      'h2.slim-video-metadata-title, .slim-video-information-title, ytd-watch-metadata h1, #title h1'
    )) || document.title.replace(/ - YouTube$/, '');
    return {
      id: id, title: title, context: '',
      channel: owner ? text(owner.querySelector('.slim-owner-channel-name, #channel-name, a')) : '',
      channelId: channelLink ? channelLink.getAttribute('href') || '' : ''
    };
  }

  function cancelHold() {
    if (holdTimer) clearTimeout(holdTimer);
    holdTimer = null;
    holdStart = null;
  }

  document.addEventListener('touchstart', function (e) {
    if (!cfg.blockMenu || e.touches.length !== 1) return;
    var video = videoUnder(e.target);
    if (!video || verdicts[video.id] !== 'allow') return;
    var t = e.touches[0];
    holdStart = { x: t.clientX, y: t.clientY };
    holdTimer = setTimeout(function () {
      holdTimer = null;
      suppressClickUntil = Date.now() + 800;
      openSheet(video);
    }, HOLD_MS);
  }, { capture: true, passive: true });
  document.addEventListener('touchmove', function (e) {
    if (!holdStart) return;
    var t = e.touches[0];
    if (Math.abs(t.clientX - holdStart.x) > 10 || Math.abs(t.clientY - holdStart.y) > 10) cancelHold();
  }, { capture: true, passive: true });
  document.addEventListener('touchend', cancelHold, true);
  document.addEventListener('touchcancel', cancelHold, true);

  // ---------------------------------------------------------------- grown-up door

  // Hold the YouTube logo for three seconds to open the parent settings. Nothing shows
  // for the first half second, so a child tapping the logo never notices it.
  var door = null;

  function closeDoor() {
    if (!door) return;
    clearTimeout(door.showTimer);
    clearTimeout(door.openTimer);
    if (door.ring) door.ring.remove();
    door = null;
  }

  function onLogo(target, x, y) {
    if (target && target.closest && target.closest(LOGO_SEL)) return true;
    // YouTube renames its header often; the top-left corner is where the logo always sits.
    return x < 150 && y < 56;
  }

  document.addEventListener('touchstart', function (e) {
    if (e.touches.length !== 1) { closeDoor(); return; }
    var t = e.touches[0];
    if (!onLogo(e.target, t.clientX, t.clientY)) return;
    closeDoor();
    door = { x: t.clientX, y: t.clientY };
    door.showTimer = setTimeout(function () {
      var ring = el('div');
      ring.id = 'st-ring';
      ring.style.left = door.x + 'px';
      ring.style.top = door.y + 'px';
      var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
      svg.setAttribute('viewBox', '0 0 56 56');
      var c = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
      c.setAttribute('cx', '28'); c.setAttribute('cy', '28'); c.setAttribute('r', '26');
      svg.appendChild(c);
      ring.appendChild(svg);
      (document.body || document.documentElement).appendChild(ring);
      door.ring = ring;
      ring.getBoundingClientRect();
      ring.classList.add('on');
    }, RING_DELAY_MS);
    door.openTimer = setTimeout(function () {
      closeDoor();
      suppressClickUntil = Date.now() + 800;
      if (bridge && bridge.openParent) {
        try { bridge.openParent(); } catch (err) { log('openParent failed ' + err); }
      } else if (window.__safetubeTestParent) {
        window.__safetubeTestParent();
      }
    }, PARENT_HOLD_MS);
  }, { capture: true, passive: true });
  document.addEventListener('touchmove', function (e) {
    if (!door) return;
    var t = e.touches[0];
    if (Math.abs(t.clientX - door.x) > 12 || Math.abs(t.clientY - door.y) > 12) closeDoor();
  }, { capture: true, passive: true });
  document.addEventListener('touchend', closeDoor, true);
  document.addEventListener('touchcancel', closeDoor, true);

  // Long-press in the WebView (and right-click with a mouse) also fires contextmenu.
  document.addEventListener('contextmenu', function (e) {
    if (door) { e.preventDefault(); return; }
    if (!cfg.blockMenu) return;
    var video = videoUnder(e.target);
    if (!video) return;
    e.preventDefault();
    if (document.getElementById('st-sheet')) return;
    cancelHold();
    suppressClickUntil = Date.now() + 800;
    openSheet(video);
  }, true);

  function closeSheet() {
    ['st-sheet', 'st-sheet-bg'].forEach(function (id) {
      var el = document.getElementById(id);
      if (el) el.remove();
    });
  }

  function openSheet(video) {
    closeSheet();
    var parent = document.body || document.documentElement;
    var bg = document.createElement('div');
    bg.id = 'st-sheet-bg';
    bg.addEventListener('click', closeSheet);
    var sheet = document.createElement('div');
    sheet.id = 'st-sheet';
    sheet.setAttribute('role', 'dialog');
    sheet.setAttribute('aria-label', 'Hide this video');
    sheet.appendChild(el('div', 'st-handle'));
    var channelName = (video.channel || '').split(/\s+[·•]\s+/)[0];

    function option(label, iconName, cls, onTap) {
      var b = el('button', cls);
      if (iconName) b.appendChild(svgIcon(iconName));
      b.appendChild(document.createTextNode(label));
      b.addEventListener('click', function (e) {
        e.preventDefault();
        e.stopPropagation();
        closeSheet();
        if (onTap) onTap();
      });
      sheet.appendChild(b);
    }
    option('Hide this video', 'eyeoff', '', function () { requestBlock('video', video); });
    option(channelName ? 'Hide everything from ' + channelName : 'Hide this channel', 'ban', '',
      function () { requestBlock('channel', { id: video.id, title: video.title, channel: channelName, channelId: video.channelId }); });
    option('Cancel', null, 'st-cancel', null);
    parent.appendChild(bg);
    parent.appendChild(sheet);
  }

  function requestBlock(kind, video) {
    var req = JSON.stringify({
      kind: kind, id: video.id, title: video.title || '', channel: video.channel || '', channelId: video.channelId || ''
    });
    if (bridge && bridge.block) {
      try { bridge.block(req); } catch (e) { log('block failed ' + e); }
    } else if (window.__safetubeTestBlock) {
      window.__safetubeTestBlock(JSON.parse(req));
    }
  }

  // Stop taps on blocked or unchecked videos before YouTube's own handlers run,
  // and the tap that ends a long-press from opening the video.
  document.addEventListener('click', function (e) {
    if (Date.now() < suppressClickUntil && !(e.target.closest && e.target.closest('#st-sheet'))) {
      e.preventDefault();
      e.stopPropagation();
      return;
    }
    var a = e.target && e.target.closest ? e.target.closest(VIDEO_LINK_SEL) : null;
    if (!a) return;
    var id = idFromUrl(a.getAttribute('href'));
    if (id && verdicts[id] !== 'allow') {
      e.preventDefault();
      e.stopPropagation();
    }
  }, true);

  // ---------------------------------------------------------------- API used by the app

  function lockPage() {
    if (!document.documentElement) return;
    document.documentElement.setAttribute('data-st-ai-unavailable', 'true');
    document.documentElement.setAttribute('data-st-watch', 'pending');
    pauseAll();
    var existing = document.getElementById('st-overlay');
    if (existing && overlayFor === 'ai-unavailable') return;
    removeOverlay();
    var box = el('div'); box.id = 'st-overlay';
    box.setAttribute('role', 'alertdialog'); box.setAttribute('aria-label', 'SafeTube checks unavailable');
    box.appendChild(el('div', 'st-h', 'YouTube is paused'));
    box.appendChild(el('div', 'st-p', 'SafeTube cannot check videos right now. A parent can review the checkers, or retry when checks are available.'));
    var parent = el('button', '', 'Parent settings');
    parent.addEventListener('click', function () {
      if (bridge && bridge.openParent) bridge.openParent();
      else if (window.__safetubeTestParent) window.__safetubeTestParent();
    });
    var retry = el('button', '', 'Retry checks');
    retry.addEventListener('click', function () { location.reload(); });
    box.appendChild(parent); box.appendChild(retry);
    (document.body || document.documentElement).appendChild(box);
    overlayFor = 'ai-unavailable';
  }

  function setAiUnavailable(value) {
    aiUnavailable = !!value;
    if (aiUnavailable) lockPage();
    else {
      document.documentElement.removeAttribute('data-st-ai-unavailable');
      removeOverlay(); scan();
    }
  }
  // Block clicks and key activation behind the safety wall, including synthetic navigation controls.
  ['click', 'keydown', 'pointerdown', 'touchstart'].forEach(function (type) {
    document.addEventListener(type, function (e) {
      if (aiUnavailable && !(e.target.closest && e.target.closest('#st-overlay'))) {
        e.preventDefault(); e.stopImmediatePropagation();
      }
    }, true);
  });

  function apply(result) {
    if (typeof result === 'string') result = JSON.parse(result);
    var now = Date.now();
    Object.keys(result).forEach(function (id) {
      var v = result[id];
      delete inFlight[id];
      if (v === 'allow' || v === 'block') {
        verdicts[id] = v;
        if (aiUnavailable) retryAt[id] = now + RETRY_MS;
        else delete retryAt[id];
      } else {
        retryAt[id] = now + RETRY_MS;
      }
    });
    // Only elements still waiting can change; re-marking those is far cheaper than a full scan.
    var waiting = document.querySelectorAll('[data-st="pending"]');
    for (var i = 0; i < waiting.length; i++) mark(waiting[i], waiting[i].getAttribute('data-st-id'));
    checkWatchPage();
  }

  /** Forget everything (rules changed) and re-check what's on screen. */
  function reset(newCfg) {
    verdicts = Object.create(null);
    retryAt = Object.create(null);
    inFlight = Object.create(null);
    queued = Object.create(null);
    queuedTargets = Object.create(null);
    if (newCfg) {
      cfg = Object.assign(cfg, typeof newCfg === 'string' ? JSON.parse(newCfg) : newCfg);
      if (cfg.aiUnavailable) aiUnavailable = true;
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

  window.__safetube = { setAiUnavailable: setAiUnavailable, apply: apply, reset: reset, scan: scan, idFromUrl: idFromUrl, _verdicts: function () { return verdicts; } };

  // ---------------------------------------------------------------- start

  installStyle();
  new MutationObserver(onMutations).observe(document, {
    childList: true, subtree: true, attributes: true, attributeFilter: ['href']
  });
  window.addEventListener('scroll', scheduleViewportScan, true);
  window.addEventListener('resize', scheduleViewportScan, true);
  ['yt-navigate-start', 'yt-navigate-finish', 'state-navigatestart', 'state-navigateend', 'popstate', 'hashchange']
    .forEach(function (ev) { window.addEventListener(ev, function () { scan(); }, true); });
  ['pushState', 'replaceState'].forEach(function (fn) {
    var orig = history[fn];
    history[fn] = function () {
      var r = orig.apply(this, arguments);
      if (isAutoplayJump()) {
        log('autoplay stopped');
        setTimeout(function () { history.back(); }, 0);
        return r;
      }
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
  setInterval(function () { if (!document.hidden) scan(); }, 1500);
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', function () { scan(); });
  scan();
})();
