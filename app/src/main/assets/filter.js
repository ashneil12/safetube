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
  var cfg = { hideShorts: true, hideComments: true, blockMenu: true, kidsCanBlock: true, debug: false };
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
      // SafeTube's own screens, in the app's colours (navy, teal, sunflower).
      '#st-overlay{position:fixed;inset:0;z-index:2147483647;display:flex;flex-direction:column;' +
      'align-items:center;justify-content:center;gap:16px;background:#14182B;color:#fff;' +
      'font:16px/1.45 Roboto,Arial,sans-serif;text-align:center;padding:24px}' +
      '#st-overlay .st-badge{width:96px;height:96px;border-radius:48px;background:#2A3150;display:flex;' +
      'align-items:center;justify-content:center;color:#FFC845}' +
      '#st-overlay .st-badge svg{width:44px;height:44px}' +
      '#st-overlay .st-h{font-size:30px;font-weight:700;letter-spacing:-.01em;line-height:1.15}' +
      '#st-overlay .st-p{font-size:18px;color:#C9CEDD;max-width:440px}' +
      '#st-overlay button{font:inherit;font-weight:700;font-size:17px;height:52px;padding:0 28px;border-radius:26px;' +
      'border:0;background:#FFC845;color:#14182B;display:inline-flex;align-items:center;gap:10px;margin-top:8px}' +
      '#st-overlay button svg{width:18px;height:18px}' +
      '#st-overlay .st-spin{width:48px;height:48px;border-radius:50%;border:4px solid #2A3150;border-top-color:#FFC845;' +
      'animation:st-spin .9s linear infinite}' +
      '@keyframes st-spin{to{transform:rotate(360deg)}}' +
      // Hold-to-hide menu
      '#st-sheet-bg{position:fixed;inset:0;z-index:2147483646;background:rgba(12,15,30,.62)}' +
      '#st-sheet{position:fixed;left:0;right:0;bottom:0;z-index:2147483647;background:#fff;color:#14182B;' +
      'border-radius:28px 28px 0 0;padding:14px 24px 22px;font:16px/1.4 Roboto,Arial,sans-serif;' +
      'max-width:560px;margin:0 auto;box-shadow:0 -12px 40px rgba(12,15,30,.25);box-sizing:border-box}' +
      '#st-sheet .st-handle{width:44px;height:5px;border-radius:3px;background:#D5D9E4;margin:0 auto 16px}' +
      '#st-sheet .st-video{display:flex;align-items:center;gap:14px;padding-bottom:16px;margin-bottom:14px;' +
      'border-bottom:1px solid #E3E7EF}' +
      '#st-sheet .st-video img{width:112px;height:63px;border-radius:10px;object-fit:cover;flex:none;background:#F3F5F9}' +
      '#st-sheet .st-title{font-weight:700;font-size:17px;overflow:hidden;display:-webkit-box;' +
      '-webkit-line-clamp:2;-webkit-box-orient:vertical}' +
      '#st-sheet .st-channel{font-size:14px;color:#5B6178;margin-top:3px}' +
      '#st-sheet button{display:flex;align-items:center;gap:14px;width:100%;text-align:left;font:inherit;font-size:17px;' +
      'font-weight:700;min-height:60px;padding:0 16px;border:0;background:#F3F5F9;color:#14182B;border-radius:16px;' +
      'margin-top:10px;box-sizing:border-box}' +
      '#st-sheet button:active{background:#E3E7EF}' +
      '#st-sheet .st-ic{width:36px;height:36px;border-radius:12px;background:#FDE8E6;color:#C23B2E;flex:none;' +
      'display:flex;align-items:center;justify-content:center}' +
      '#st-sheet .st-ic svg{width:20px;height:20px}' +
      '#st-sheet button.st-cancel{justify-content:center;background:none;font-weight:500;font-size:16px;color:#5B6178;min-height:52px}' +
      '#st-sheet .st-foot{display:flex;align-items:center;justify-content:center;gap:8px;font-size:14px;color:#5B6178}' +
      '#st-sheet .st-foot svg{width:16px;height:16px}' +
      '#st-block-btn{position:fixed;left:12px;bottom:72px;z-index:2147483645;height:44px;padding:0 16px 0 12px;' +
      'border-radius:22px;border:0;background:rgba(20,24,43,.82);color:#fff;font:700 15px Roboto,Arial,sans-serif;' +
      'display:flex;align-items:center;gap:8px}' +
      '#st-block-btn svg{width:20px;height:20px}'
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
      showBlockButton(false);
      return;
    }
    var v = verdicts[id];
    if (v === 'allow') {
      if (html.getAttribute('data-st-watch') !== 'allow') html.setAttribute('data-st-watch', 'allow');
      removeOverlay();
      showBlockButton(true);
      return;
    }
    showBlockButton(false);
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

  // Small stroke icons, built with DOM calls because YouTube's page forbids innerHTML.
  var ICONS = {
    shield: ['M12 3l8 3v6c0 5-3.4 8.6-8 10-4.6-1.4-8-5-8-10V6z', 'M9 12l2 2 4-4'],
    home: ['M3 11l9-7 9 7v9a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z'],
    eyeoff: ['M3 3l18 18', 'M10.6 5.1A10.7 10.7 0 0 1 12 5c6 0 9.5 7 9.5 7a17.6 17.6 0 0 1-3 3.9M6.6 6.6C3.9 8.4 2.5 12 2.5 12S6 19 12 19c1.9 0 3.6-.7 5-1.6', 'M9.9 9.9a3 3 0 0 0 4.2 4.2'],
    ban: ['M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z', 'M5.6 5.6l12.8 12.8'],
    lock: ['M7 11h10a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-6a2 2 0 0 1 2-2z', 'M8 11V8a4 4 0 0 1 8 0v3'],
    retry: ['M3 12a9 9 0 0 1 15.5-6.2L21 8', 'M21 3v5h-5', 'M21 12a9 9 0 0 1-15.5 6.2L3 16', 'M3 21v-5h5']
  };
  function svgIcon(name) {
    var ns = 'http://www.w3.org/2000/svg';
    var svg = document.createElementNS(ns, 'svg');
    svg.setAttribute('viewBox', '0 0 24 24');
    svg.setAttribute('fill', 'none');
    svg.setAttribute('stroke', 'currentColor');
    svg.setAttribute('stroke-width', '2');
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
      box.appendChild(el('div', 'st-p', 'Just checking this one…'));
      return;
    }
    var badge = el('div', 'st-badge');
    badge.appendChild(svgIcon(kind === 'error' ? 'retry' : 'shield'));
    box.appendChild(badge);
    box.appendChild(el('div', 'st-h', kind === 'error' ? "This one couldn't be checked" : "This one isn't available"));
    box.appendChild(el('div', 'st-p', kind === 'error' ? 'Try again in a moment, or pick something else.'
      : 'Let’s find something else to watch.'));
    var b = el('button');
    b.appendChild(svgIcon('home'));
    b.appendChild(document.createTextNode('Back to videos'));
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
    var card = target.closest(CARD_SEL);
    var link = card ? cardVideoLink(card) : target.closest(VIDEO_LINK_SEL);
    var id = link ? idFromUrl(link.getAttribute('href')) : null;
    if (!id) return null;
    return describe(card || link, link, id);
  }

  /** The video on the watch page, for the block button. */
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

  // Long-press in the WebView (and right-click with a mouse) also fires contextmenu.
  document.addEventListener('contextmenu', function (e) {
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
    var head = el('div', 'st-video');
    var img = document.createElement('img');
    img.alt = '';
    img.src = 'https://i.ytimg.com/vi/' + video.id + '/mqdefault.jpg';
    head.appendChild(img);
    var text = el('div');
    text.appendChild(el('div', 'st-title', video.title || 'This video'));
    if (channelName) text.appendChild(el('div', 'st-channel', channelName));
    head.appendChild(text);
    sheet.appendChild(head);

    function option(label, iconName, cls, onTap) {
      var b = el('button', cls);
      if (iconName) {
        var ic = el('span', 'st-ic');
        ic.appendChild(svgIcon(iconName));
        b.appendChild(ic);
      }
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
    var foot = el('div', 'st-foot');
    foot.appendChild(svgIcon('lock'));
    foot.appendChild(document.createTextNode(cfg.kidsCanBlock === false
      ? 'A grown-up will need to enter the PIN.' : 'Only a grown-up can bring it back.'));
    sheet.appendChild(foot);
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

  function showBlockButton(show) {
    var btn = document.getElementById('st-block-btn');
    if (!show || !cfg.blockMenu) { if (btn) btn.remove(); return; }
    if (btn) return;
    btn = document.createElement('button');
    btn.id = 'st-block-btn';
    btn.setAttribute('aria-label', 'Hide this video or channel');
    btn.appendChild(svgIcon('eyeoff'));
    btn.appendChild(document.createTextNode('Hide'));
    btn.addEventListener('click', function (e) {
      e.preventDefault();
      e.stopPropagation();
      var v = currentVideo();
      if (v) openSheet(v);
    });
    (document.body || document.documentElement).appendChild(btn);
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
