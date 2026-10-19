// Runs the parent screens (app/src/main/assets/parent) in headless Chromium with a stand-in for
// the app's SafeTubeParent bridge, and walks through setup, unlock, every page and a PIN-gated hide.
//
//   cd tools/filter-test && npm install    (Playwright lives there)
//   node ../parent-test/run.js [--screenshots]

const http = require('http');
const fs = require('fs');
const path = require('path');
const { chromium } = require(require.resolve('playwright', { paths: [path.join(__dirname, '../filter-test')] }));

const ROOT = path.join(__dirname, '../../app/src/main/assets/parent');
const SHOTS = process.argv.includes('--screenshots');
const TYPES = { '.html': 'text/html', '.css': 'text/css', '.js': 'text/javascript', '.woff2': 'font/woff2' };

let failures = 0;
function check(name, cond) {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}`);
  if (!cond) failures++;
}

// The app side, in the page: settings, lists and the calls the screens make.
function fakeApp(mode) {
  const settings = {
    rules: 'Nothing scary.', keywords: ['halloween', 'witch'], strictness: 'STRICT',
    provider: { type: 'NONE', accountId: '', apiKey: '', baseUrl: 'https://openrouter.ai/api/v1', model: '', sendThumbnails: true },
    helper: { type: 'NONE', apiKey: '', baseUrl: 'https://generativelanguage.googleapis.com/v1beta/openai', model: 'gemini-2.5-flash' },
    hideShorts: true, hideComments: true, kidsCanBlock: true,
  };
  window.__log = [];
  window.__pin = mode === 'setup' ? null : '1234';
  let unlocked = false;
  window.SafeTubeParent = {
    state: () => JSON.stringify({
      mode, pinLength: 4, version: '0.2.0',
      current: mode === 'unlock' ? { videoId: 'WHEELSBUS01', channelRef: null } : null,
      block: mode === 'block' ? { kind: 'channel', title: 'Monster Trucks', channel: 'Big Wheel Bros' } : null,
      settings: unlocked || mode === 'setup' ? window.__settings : null,
      defaults: { geminiBaseUrl: 'https://generativelanguage.googleapis.com/v1beta/openai', geminiModel: 'gemini-2.5-flash',
        models: { CLEF: 'clef', JEV: 'typesafe/jev-1.14', ANTHROPIC: 'claude-haiku-5-5' } },
    }),
    lists: () => JSON.stringify({
      stats: { checkedToday: 186, hiddenToday: 14 },
      hidden: [
        { id: 'HALLOWEEN01', title: 'Five Little Pumpkins | Halloween Song', channel: 'Sing Along Street', reason: 'Keyword: halloween' },
        { id: 'POTIONSHOP1', title: 'The Little Potion Shop – Episode 4', channel: 'Toon Tales', reason: 'Spells and potions' },
        { id: 'TRUCKS00001', title: 'Monster Truck Mud Jumps', channel: 'Big Wheel Bros', reason: 'Set by child' },
        { id: 'PRANKS00001', title: '10 Craziest Pranks <script>alert(1)</script>', channel: 'PrankZone', reason: 'Blocked channel: PrankZone' },
      ],
      blocked: [{ id: 'TRUCKS00001', title: 'Monster Truck Mud Jumps', by: 'child' }],
      allowed: [{ id: 'NUMBERS0001', title: 'Counting to 20' }],
      channels: ['@PrankZone', 'PrankZone'],
    }),
    checkPin: (p) => { const ok = p === window.__pin; if (ok) unlocked = true; return ok; },
    setPin: (p) => { window.__pin = p; unlocked = true; window.__log.push('setPin ' + p); },
    save: (json) => { window.__settings = JSON.parse(json); window.__log.push('save'); },
    call: (name, args, id) => {
      window.__log.push('call ' + name);
      const answers = {
        lookup: { title: 'Wheels on the Bus', channel: 'Little Wheels TV', channelId: '@LittleWheels' },
        suggest: { keywords: ['trick or treat', 'haunted', 'spell book'] },
        testAi: { results: [{ title: 'Five Little Pumpkins', hidden: true }, { title: 'Counting to 20', hidden: false }] },
        blockLink: { ok: true, message: '@Someone is now hidden.' },
      };
      setTimeout(() => window.__parent.done(id, JSON.stringify(answers[name])), 30);
    },
    allow: (id) => window.__log.push('allow ' + id),
    undo: (id) => window.__log.push('undo ' + id),
    blockVideo: (id) => window.__log.push('blockVideo ' + id),
    blockChannel: (j) => window.__log.push('blockChannel ' + j),
    unblockChannel: (c) => window.__log.push('unblock ' + c),
    forgetAi: () => window.__log.push('forget'),
    openLink: (u) => window.__log.push('open ' + u),
    finishSetup: () => window.__log.push('finishSetup'),
    close: () => window.__log.push('close'),
    confirmBlock: () => window.__log.push('confirmBlock'),
  };
  window.__settings = settings;
}

(async () => {
  const server = http.createServer((req, res) => {
    const file = path.join(ROOT, decodeURIComponent(req.url.split('?')[0]));
    if (!file.startsWith(ROOT) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) { res.writeHead(404); res.end(); return; }
    res.writeHead(200, { 'content-type': TYPES[path.extname(file)] || 'application/octet-stream' });
    res.end(fs.readFileSync(file));
  }).listen(0);
  const url = `http://127.0.0.1:${server.address().port}/index.html`;
  const browser = await chromium.launch(
    fs.existsSync('/opt/pw-browsers/chromium') ? { executablePath: '/opt/pw-browsers/chromium' } : {}
  );
  const shots = path.join(__dirname, 'screenshots');
  if (SHOTS) fs.mkdirSync(shots, { recursive: true });

  async function open(mode, viewport) {
    const page = await browser.newPage({ viewport: viewport || { width: 1280, height: 800 } });
    page.errors = [];
    page.on('pageerror', (e) => page.errors.push(String(e)));
    page.on('console', (m) => { if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) page.errors.push(m.text()); });
    // Missing app files (a font, the stylesheet) would break the screens. YouTube thumbnails for the
    // made-up test videos 404 when there is network, and the favicon request isn't ours.
    page.on('response', (r) => {
      const u = r.url();
      if (r.status() >= 400 && u.startsWith('http://127.0.0.1') && !u.endsWith('/favicon.ico')) page.errors.push(r.status() + ' ' + u);
    });
    await page.addInitScript(fakeApp, mode);
    await page.goto(url);
    await page.waitForTimeout(150);
    return page;
  }
  const type = async (page, digits) => { for (const d of digits) await page.click(`.keypad [data-k="${d}"]`); };
  const shot = async (page, name) => { if (SHOTS) await page.screenshot({ path: path.join(shots, name + '.png'), fullPage: true }); };
  const log = (page) => page.evaluate(() => window.__log.slice());

  // 1. First run: create a PIN, choose what to hide, set up the checker.
  let page = await open('setup');
  check('setup starts by creating a PIN', (await page.textContent('h1')) === 'Create a parent PIN');
  await shot(page, '01-create-pin');
  await type(page, '4321');
  await page.waitForTimeout(200);
  check('then asks for it again', (await page.textContent('h1')) === 'Once more');
  await type(page, '1111');
  check('a mismatch is explained', (await page.textContent('.pin-error')).includes('didn’t match'));
  await page.waitForTimeout(1000);
  await type(page, '4321');
  await page.waitForTimeout(200);
  await type(page, '4321');
  await page.waitForTimeout(200);
  check('matching PINs are saved', (await log(page)).includes('setPin 4321'));
  check('step 2 asks what to hide', (await page.textContent('h1')) === 'What should SafeTube hide?');
  await page.click('[data-p="pranks"]');
  check('picking a preset writes the rules for you',
    (await page.inputValue('#own')).includes('No prank videos') && (await page.inputValue('#own')).includes('witches'));
  await shot(page, '02-what-to-hide');
  await page.click('[data-next]');
  const saved = await page.evaluate(() => window.__settings);
  check('presets add their keywords', saved.keywords.includes('prank') && saved.keywords.includes('halloween'));
  check('step 3 offers the smart checker with Clef picked', (await page.getAttribute('[data-type="CLEF"]', 'aria-checked')) === 'true');
  await page.fill('[data-field="accountId"]', 'acct123');
  await page.fill('[data-field="apiKey"]', 'secret');
  await page.dispatchEvent('[data-field="apiKey"]', 'change');
  await page.dispatchEvent('[data-field="accountId"]', 'change');
  await shot(page, '03-smart-checker-setup');
  await page.click('[data-done]');
  const after = await page.evaluate(() => window.__settings.provider);
  check('the checker keys are saved', after.type === 'CLEF' && after.accountId === 'acct123' && after.apiKey === 'secret');
  check('finishing setup goes back to videos', (await log(page)).includes('finishSetup'));
  check('no page errors during setup', page.errors.length === 0);
  if (page.errors.length) console.log(page.errors);
  await page.close();

  // 2. Unlock, then every settings page.
  page = await open('unlock');
  check('settings start locked', (await page.textContent('h1')) === 'Grown-ups only');
  check('no settings are shared before the PIN', await page.evaluate(() => JSON.parse(window.SafeTubeParent.state()).settings === null));
  await shot(page, '04-pin');
  await type(page, '9999');
  check('a wrong PIN is refused', (await page.textContent('.pin-error')).includes('isn’t right'));
  await type(page, '1234');
  await page.waitForTimeout(200);
  check('the right PIN opens the overview', (await page.textContent('h1')) === 'Overview');
  check('the open video can be hidden in one tap', (await page.textContent('.now-playing')).includes('Wheels on the Bus'));
  check('video titles are shown as text, never as HTML',
    (await page.$$('script')).length === 1 && (await page.textContent('#recent')).includes('<script>'));
  check('reasons are labelled', (await page.textContent('#recent')).includes('Keyword “halloween”') &&
    (await page.textContent('#recent')).includes('Hidden by your child'));
  await shot(page, '05-overview');
  await page.click('[data-strict="BALANCED"]');
  check('strictness saves', (await page.evaluate(() => window.__settings.strictness)) === 'BALANCED');
  await page.click('[data-hide-channel]');
  check('hiding the open video’s channel sends its handle and name',
    (await log(page)).some((l) => l.includes('blockChannel') && l.includes('@LittleWheels')));

  await page.click('[data-page="rules"]');
  check('what-to-hide page opens', (await page.textContent('h1')) === 'What to hide');
  await page.fill('.chip-input', 'ghost');
  await page.press('.chip-input', 'Enter');
  check('typing a keyword adds it', (await page.evaluate(() => window.__settings.keywords)).includes('ghost'));
  await page.click('[data-suggest]');
  check('without a helper it explains how to set one up', (await page.textContent('#suggest')).includes('isn’t set up'));
  await page.click('[data-switch="kidsCanBlock"]');
  check('switches save', (await page.evaluate(() => window.__settings.kidsCanBlock)) === false);
  await shot(page, '06-what-to-hide');

  await page.click('[data-page="checker"]');
  await page.click('[data-gemini]');
  await page.fill('[data-h="apiKey"]', 'gemini-key');
  await page.click('[data-done]');
  check('Gemini helper is one tap plus a key', (await page.evaluate(() => window.__settings.helper.apiKey)) === 'gemini-key' &&
    (await page.textContent('#helper')).includes('Connected'));
  await page.click('[data-type="CLEF"]');
  await page.click('[data-test]');
  await page.waitForTimeout(100);
  check('testing shows what would be hidden', (await page.textContent('[data-out]')).includes('Hidden'));
  await shot(page, '07-smart-checker');

  await page.click('[data-page="rules"]');
  await page.click('[data-suggest]');
  await page.waitForTimeout(100);
  check('suggested keywords appear', (await page.textContent('#suggest')).includes('haunted'));
  await page.click('[data-all]');
  check('"Add all" adds them', (await page.evaluate(() => window.__settings.keywords)).includes('spell book'));

  await page.click('[data-page="lists"]');
  check('videos & channels page opens', (await page.textContent('h1')) === 'Videos & channels');
  await page.fill('[name="link"]', '@Someone');
  await page.click('[data-paste] button');
  await page.waitForTimeout(100);
  check('pasting a channel hides it', (await log(page)).includes('call blockLink'));
  await page.click('[data-unblock="@PrankZone"]');
  check('channels can be unblocked', (await log(page)).includes('unblock @PrankZone'));
  await page.click('[data-undo="TRUCKS00001"]');
  check('a video the child hid can be brought back', (await log(page)).includes('undo TRUCKS00001'));
  await shot(page, '08-videos-and-channels');

  await page.click('[data-page="settings"]');
  await shot(page, '09-settings');
  await page.click('[data-close]');
  check('"Back to videos" closes settings', (await log(page)).includes('close'));
  check('no page errors in settings', page.errors.length === 0);
  if (page.errors.length) console.log(page.errors);
  await page.close();

  // 3. Hiding needs the PIN when children can't hide things themselves.
  page = await open('block');
  check('a PIN-gated hide names what will be hidden', (await page.textContent('.pin-copy')).includes('Big Wheel Bros'));
  await type(page, '1234');
  check('the right PIN confirms the hide', (await log(page)).includes('confirmBlock'));
  await page.close();

  // 4. Fire 7 in portrait.
  page = await open('unlock', { width: 600, height: 1024 });
  await type(page, '1234');
  await page.waitForTimeout(200);
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  check('small tablets get no sideways scrolling', overflow <= 0);
  await shot(page, '10-small-tablet');
  await page.close();

  await browser.close();
  server.close();
  console.log(failures ? `\n${failures} check(s) failed` : '\nAll checks passed');
  process.exit(failures ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
