// Runs filter.js in headless Chromium against a mock YouTube page and checks the
// "unknown = hidden" behaviour, blocking, the watch page and Shorts handling.
//
//   cd tools/filter-test && npm install playwright && node run.js
//   (add --screenshots to save before/after images in ./screenshots)

const http = require('http');
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const FILTER = fs.readFileSync(path.join(__dirname, '../../app/src/main/assets/filter.js'), 'utf8');
const PAGE = fs.readFileSync(path.join(__dirname, 'mock-youtube.html'), 'utf8');
const SHOTS = process.argv.includes('--screenshots');

// Stand-in for the app: blocks anything spooky by title, leaves MYSTERY0001 undecided.
function decide(v) {
  if (v.id === 'MYSTERY0001') return 'unknown';
  if (v.channelId === '/@AnnoyingChannel') return 'block'; // a channel the parent blocked
  return /halloween|witch|spooky|pumpkin/i.test(v.title + ' ' + v.context) ? 'block' : 'allow';
}

let failures = 0;
function check(name, cond) {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}`);
  if (!cond) failures++;
}

(async () => {
  const server = http.createServer((req, res) => {
    if (req.url.startsWith('/youtubei/v1/player')) {
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ videoDetails: { videoId: 'WHEELSBUS01' }, playerAds: [{}], adPlacements: [{}] }));
      return;
    }
    res.writeHead(200, { 'content-type': 'text/html' });
    res.end(PAGE);
  }).listen(0);
  const base = `http://127.0.0.1:${server.address().port}`;

  const browser = await chromium.launch(
    fs.existsSync('/opt/pw-browsers/chromium') ? { executablePath: '/opt/pw-browsers/chromium' } : {}
  );
  const page = await browser.newPage({ viewport: { width: 800, height: 1100 } });

  // The fake Android bridge records what the page asks for; the test answers when it chooses.
  await page.addInitScript(() => {
    window.__submitted = [];
    window.__blocks = [];
    window.__watched = [];
    window.__safetubeTestBlock = (req) => window.__blocks.push(req);
    window.__parentOpened = 0;
    window.__safetubeTestParent = () => window.__parentOpened++;
    window.SafeTubeAndroid = {
      config: () => JSON.stringify({ hideShorts: true, hideComments: true, watchedAfterMs: 300 }),
      watched: (json) => window.__watched.push(JSON.parse(json)),
      submit: (json) => window.__submitted.push(...JSON.parse(json)),
    };
  });
  await page.addInitScript(FILTER); // document start, like WebViewCompat.addDocumentStartJavaScript

  const vis = (sel) => page.$eval(sel, (el) => {
    const s = getComputedStyle(el);
    return s.display === 'none' ? 'removed' : s.visibility === 'hidden' ? 'hidden' : 'visible';
  });
  const answer = async () => {
    const asked = await page.evaluate(() => window.__submitted.splice(0));
    const result = {};
    asked.forEach((v) => { result[v.id] = decide(v); });
    await page.evaluate((r) => window.__safetube.apply(r), result);
    await page.waitForTimeout(50);
    return asked;
  };

  await page.goto(base + '/');
  await page.waitForTimeout(200);

  // 1. Nothing unchecked is visible.
  for (const id of ['WHEELSBUS01', 'HALLOWEEN01', 'BABYSHARK01', 'WITCHBREW01']) {
    check(`before decisions, ${id} is hidden`, (await vis(`#card-${id}`)) === 'hidden');
  }
  check('loose link to an unchecked video is hidden', (await vis('#looseLink')) === 'hidden');
  if (SHOTS) {
    fs.mkdirSync(path.join(__dirname, 'screenshots'), { recursive: true });
    await page.screenshot({ path: path.join(__dirname, 'screenshots/1-before.png') });
  }

  // 2. Titles and channels are extracted for the AI.
  const asked = await answer();
  const pumpkins = asked.find((v) => v.id === 'HALLOWEEN01');
  check('page sent the title it saw', pumpkins && pumpkins.title === 'Five Little Pumpkins | Halloween Song');
  check('page sent the channel it saw', pumpkins && pumpkins.channel === 'Super Simple Songs');
  check('page sent the channel handle', pumpkins && pumpkins.channelId === '/@SuperSimpleSongs');
  check('each video is asked about once', asked.filter((v) => v.id === 'WHEELSBUS01').length === 1);

  // 3. Decisions applied.
  check('allowed video is shown', (await vis('#card-WHEELSBUS01')) === 'visible');
  check('allowed video is shown (2)', (await vis('#card-NUMBERS0001')) === 'visible');
  check('blocked video is removed', (await vis('#card-HALLOWEEN01')) === 'removed');
  check('blocked video is removed (2)', (await vis('#card-WITCHBREW01')) === 'removed');
  check('video from a blocked channel is removed', (await vis('#card-BADCHANNEL1')) === 'removed');
  check('undecided video stays hidden', (await vis('#card-MYSTERY0001')) === 'hidden');
  check('loose blocked link stays hidden', (await vis('#looseLink')) === 'hidden');
  check('Shorts shelf is removed', (await vis('#shorts')) === 'removed');
  check('comments are removed', (await vis('#comments')) === 'removed');
  check('ads are removed', (await vis('#ad')) === 'removed');
  if (SHOTS) await page.screenshot({ path: path.join(__dirname, 'screenshots/2-after.png') });

  // 4. Undecided videos are not re-asked immediately, but are retried later.
  await page.waitForTimeout(1200);
  const reasked = await page.evaluate(() => window.__submitted.map((v) => v.id));
  check('undecided video is not hammered with retries', !reasked.includes('MYSTERY0001'));

  // 5. Infinite scroll: new cards are hidden until checked.
  await page.evaluate(() => document.getElementById('grid')
    .appendChild(window.makeCard('NEWSONG0001', 'Brand New Song', 'Some Channel', '#000')));
  await page.waitForTimeout(100);
  check('newly loaded card is hidden', (await vis('#card-NEWSONG0001')) === 'hidden');
  await answer();
  check('newly loaded card appears once allowed', (await vis('#card-NEWSONG0001')) === 'visible');

  // 6. YouTube reuses card elements for different videos.
  await page.evaluate(() => document.querySelectorAll('#card-BABYSHARK01 a')
    .forEach((a) => a.setAttribute('href', '/watch?v=WITCHBREW01')));
  await page.waitForTimeout(100);
  check('recycled card now pointing at a blocked video is removed', (await vis('#card-BABYSHARK01')) === 'removed');

  // 7. Tapping an unchecked video does nothing.
  await page.evaluate(() => document.getElementById('grid')
    .appendChild(window.makeCard('UNCHECKED01', 'Unchecked', 'X', '#000')));
  await page.waitForTimeout(50);
  const clickPrevented = await page.evaluate(() => {
    const a = document.querySelector('#card-UNCHECKED01 a');
    const ev = new MouseEvent('click', { bubbles: true, cancelable: true });
    a.dispatchEvent(ev);
    return ev.defaultPrevented;
  });
  check('tap on an unchecked video is cancelled', clickPrevented);
  await answer();

  // 8. Watch page (YouTube navigates with pushState).
  await page.evaluate(() => history.pushState({}, '', '/watch?v=HALLOWEEN01'));
  await page.waitForTimeout(100);
  check('player hidden on a blocked video', (await vis('#player')) === 'hidden');
  const blockedText = await page.$eval('#st-overlay', (el) => el.textContent).catch(() => '');
  check('blocked video shows "isn\'t available"', blockedText.includes("isn't available"));
  check('blocked screen offers a way back', blockedText.includes('Go back'));
  if (SHOTS) await page.screenshot({ path: path.join(__dirname, 'screenshots/4-blocked.png') });

  await page.evaluate(() => history.pushState({}, '', '/watch?v=FRESHVIDEO1'));
  await page.waitForTimeout(100);
  check('player hidden while a new video is checked', (await vis('#player')) === 'hidden');
  const watchAsk = await page.evaluate(() => window.__submitted.find((v) => v.id === 'FRESHVIDEO1'));
  check('watch page asks the app to look the video up', !!watchAsk && watchAsk.title === '');
  const playing = await page.evaluate(async () => {
    const v = document.getElementById('vid');
    let paused = false;
    v.addEventListener('pause', () => { paused = true; });
    v.dispatchEvent(new Event('play'));
    return paused || v.paused;
  });
  check('playback is stopped while unchecked', playing);
  await page.evaluate(() => window.__safetube.apply({ FRESHVIDEO1: 'allow' }));
  await page.waitForTimeout(50);
  check('player shown once the video is allowed', (await vis('#player')) === 'visible');
  check('overlay removed once allowed', (await page.$('#st-overlay')) === null);

  // 8b. Watch history and autoplay.
  await page.waitForTimeout(500);
  check('a watched video is reported for the history once',
    await page.evaluate(() => window.__watched.length === 1 && window.__watched[0].id === 'FRESHVIDEO1'));
  await page.evaluate(() => window.__safetube.apply({ WHEELSBUS01: 'allow' }));
  await page.evaluate(() => document.getElementById('vid').dispatchEvent(new Event('ended')));
  await page.evaluate(() => history.pushState({}, '', '/watch?v=WHEELSBUS01'));
  await page.waitForTimeout(150);
  check('autoplay to the next video is undone', page.url().includes('v=FRESHVIDEO1'));
  await page.evaluate(() => document.getElementById('vid').dispatchEvent(new Event('ended')));
  await page.evaluate(() => document.dispatchEvent(new Event('mousedown')));
  await page.evaluate(() => history.pushState({}, '', '/watch?v=WHEELSBUS01'));
  await page.waitForTimeout(150);
  check('a video the child picks after one ends still opens', page.url().includes('v=WHEELSBUS01'));
  await page.evaluate(() => history.pushState({}, '', '/watch?v=FRESHVIDEO1'));
  await page.waitForTimeout(100);

  // 9. Hold menu. Nothing extra shows on the watch page; holding the title opens the menu.
  check('no extra buttons on the watch page', (await page.$('#st-block-btn')) === null);
  await page.dispatchEvent('#watchinfo', 'contextmenu');
  check('holding the playing video\'s title opens the menu', (await page.$('#st-sheet')) !== null);
  await page.click('#st-sheet >> text=Hide this video');
  let blocks = await page.evaluate(() => window.__blocks.splice(0));
  check('"Hide this video" asks the app to block the playing video',
    blocks.length === 1 && blocks[0].kind === 'video' && blocks[0].id === 'FRESHVIDEO1');
  check('menu closes after choosing', (await page.$('#st-sheet')) === null);

  await page.evaluate(() => history.pushState({}, '', '/'));
  await page.waitForTimeout(100);
  await page.dispatchEvent('#card-WHEELSBUS01 h3', 'contextmenu');
  const sheetText = await page.$eval('#st-sheet', (el) => el.textContent).catch(() => '');
  check('menu names the channel', sheetText.includes('Hide everything from Super Simple Songs'));
  if (SHOTS) await page.screenshot({ path: path.join(__dirname, 'screenshots/3-hold-menu.png') });
  const leaked = await page.evaluate(() => {
    const ev = new MouseEvent('click', { bubbles: true, cancelable: true });
    document.querySelector('#card-WHEELSBUS01 a').dispatchEvent(ev);
    return !ev.defaultPrevented;
  });
  check('the tap that ends a long-press does not open the video', !leaked);
  await page.click('#st-sheet >> text=Hide everything from');
  blocks = await page.evaluate(() => window.__blocks.splice(0));
  check('"Hide everything from" sends the channel handle',
    blocks.length === 1 && blocks[0].kind === 'channel' && blocks[0].channelId === '/@SuperSimpleSongs' &&
    blocks[0].channel === 'Super Simple Songs');
  await page.dispatchEvent('#card-NUMBERS0001 h3', 'contextmenu');
  await page.click('#st-sheet >> text=Cancel');
  check('Cancel closes the menu without blocking',
    (await page.$('#st-sheet')) === null && (await page.evaluate(() => window.__blocks.length)) === 0);

  // 9b. Holding the YouTube logo for three seconds opens the parent settings; a tap does not.
  const touch = (type, x, y) => page.evaluate(([type, x, y]) => {
    const ev = new Event(type, { bubbles: true, cancelable: true });
    Object.defineProperty(ev, 'touches', { value: type === 'touchend' ? [] : [{ clientX: x, clientY: y }] });
    document.elementFromPoint(x, y).dispatchEvent(ev);
  }, [type, x, y]);
  await touch('touchstart', 30, 20);
  await page.waitForTimeout(1000);
  check('holding the logo shows a ring after a moment', (await page.$('#st-ring')) !== null);
  await touch('touchend', 30, 20);
  await page.waitForTimeout(2300);
  check('letting go early does not open settings',
    (await page.evaluate(() => window.__parentOpened)) === 0 && (await page.$('#st-ring')) === null);
  await touch('touchstart', 30, 20);
  await page.waitForTimeout(3200);
  check('holding the logo for three seconds opens settings', (await page.evaluate(() => window.__parentOpened)) === 1);
  await touch('touchstart', 400, 600);
  await page.waitForTimeout(3200);
  check('holding elsewhere does not', (await page.evaluate(() => window.__parentOpened)) === 1);
  await touch('touchend', 400, 600);

  // 9c. Ads: removed from the player's data however it arrives, and skipped if one plays.
  const ads = await page.evaluate(async () => {
    window.ytInitialPlayerResponse = { videoDetails: {}, adPlacements: [{}], playerAds: [{}] };
    const initial = window.ytInitialPlayerResponse;
    const parsed = JSON.parse('{"playerResponse":{"adSlots":[1],"videoDetails":{}},"other":1}');
    const fetched = await (await fetch('/youtubei/v1/player?key=x', { method: 'POST' })).json();
    return {
      initial: !('adPlacements' in initial) && !('playerAds' in initial) && 'videoDetails' in initial,
      parsed: !('adSlots' in parsed.playerResponse) && parsed.other === 1,
      fetched: !('playerAds' in fetched) && !('adPlacements' in fetched) && fetched.videoDetails.videoId === 'WHEELSBUS01',
      plain: JSON.parse('[1,2]').length === 2,
    };
  });
  check('ads are removed from the first page load', ads.initial);
  check('ads are removed from parsed player data', ads.parsed);
  check('ads are removed from player data the page fetches', ads.fetched);
  check('other data is left alone', ads.plain);
  await page.evaluate(() => {
    const p = document.createElement('div');
    p.className = 'html5-video-player ad-showing';
    p.innerHTML = '<video id="advid"></video><button class="ytp-ad-skip-button">Skip</button>';
    p.querySelector('button').onclick = () => { window.__skipped = true; };
    document.body.prepend(p);
  });
  await page.waitForTimeout(400);
  check('an ad that plays anyway is muted and skipped',
    await page.evaluate(() => document.getElementById('advid').muted && window.__skipped === true));
  await page.evaluate(() => document.querySelector('.html5-video-player').classList.remove('ad-showing'));
  await page.waitForTimeout(400);
  check('sound comes back after the ad', await page.evaluate(() => !document.getElementById('advid').muted));
  await page.evaluate(() => document.querySelector('.html5-video-player').remove());

  // 10. Settings change: everything is re-checked.
  await page.evaluate(() => history.pushState({}, '', '/'));
  await page.evaluate(() => { window.__submitted.length = 0; window.__safetube.reset({ hideShorts: false }); });
  await page.waitForTimeout(150);
  check('after reset, previously allowed video is hidden again', (await vis('#card-WHEELSBUS01')) === 'hidden');
  check('after reset, Shorts shelf is back when allowed', (await vis('#shorts')) === 'visible');

  // 11. Opening a Short directly goes to the normal player when Shorts are hidden.
  await page.goto(base + '/shorts/WHEELSBUS01');
  await page.waitForURL(/\/watch\?v=WHEELSBUS01/, { timeout: 3000 }).catch(() => {});
  check('a Short opens in the normal player instead', page.url().endsWith('/watch?v=WHEELSBUS01'));

  await browser.close();
  server.close();
  console.log(failures ? `\n${failures} check(s) failed` : '\nAll checks passed');
  process.exit(failures ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
