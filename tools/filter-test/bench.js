// Rough speed check: tap delay and script time on a busy page with a 6x slower CPU, with and
// without the filter. Run: node bench.js ../../app/src/main/assets/filter.js
const http = require('http'); const fs = require('fs'); const path = require('path');
const { chromium } = require('playwright');
const FILTER = fs.readFileSync(process.argv[2], 'utf8');
const PAGE = fs.readFileSync(path.join(process.cwd(), 'mock-youtube.html'), 'utf8');
(async () => {
  const server = http.createServer((q, r) => { r.writeHead(200, {'content-type':'text/html'}); r.end(PAGE); }).listen(0);
  const base = `http://127.0.0.1:${server.address().port}`;
  const browser = await chromium.launch(fs.existsSync('/opt/pw-browsers/chromium') ? { executablePath: '/opt/pw-browsers/chromium' } : {});
  for (const withFilter of [false, true]) {
    const ctx = await browser.newContext({ viewport: { width: 800, height: 1280 }, hasTouch: true, isMobile: true });
    const page = await ctx.newPage();
    if (withFilter) {
      await page.addInitScript(() => {
        window.SafeTubeAndroid = { config: () => '{}', submit: (j) => {
          const r = {}; JSON.parse(j).forEach(v => r[v.id] = 'allow');
          setTimeout(() => window.__safetube.apply(r), 30);
        } };
      });
      await page.addInitScript(FILTER);
    }
    const cdp = await ctx.newCDPSession(page);
    await page.goto(base + '/');
    // A busy page: 400 cards, with YouTube-like churn (new cards + text updates) every 50ms.
    await page.evaluate(() => {
      let n = 0;
      const id = () => ('X' + (n++).toString().padStart(10, '0'));
      for (let i = 0; i < 400; i++) document.getElementById('grid').appendChild(window.makeCard(id(), 'Song ' + i, 'Chan', '#345'));
      window.__churn = setInterval(() => {
        const g = document.getElementById('grid');
        g.appendChild(window.makeCard(id(), 'More', 'Chan', '#345'));
        g.firstElementChild.querySelector('h3').textContent = 'tick ' + n;
      }, 50);
    });
    await page.waitForTimeout(1500);
    await cdp.send('Emulation.setCPUThrottlingRate', { rate: 6 });
    await cdp.send('Performance.enable');
    const before = (await cdp.send('Performance.getMetrics')).metrics;
    // Tap latency: time from a tap to its click handler running.
    const lat = [];
    for (let i = 0; i < 15; i++) {
      await page.evaluate(() => { window.__t = null; document.querySelector('header').onclick = () => { window.__t = performance.now(); }; });
      const t0 = await page.evaluate(() => performance.now());
      await page.tap('header');
      const t = await page.evaluate(() => window.__t);
      lat.push(t - t0);
      await page.waitForTimeout(150);
    }
    const after = (await cdp.send('Performance.getMetrics')).metrics;
    const m = (arr, k) => arr.find(x => x.name === k).value;
    const d = (k) => (m(after, k) - m(before, k)).toFixed(2);
    lat.sort((a, b) => a - b);
    console.log(`${withFilter ? 'with filter   ' : 'without filter'}  tap median ${lat[7].toFixed(0)}ms  worst ${lat[14].toFixed(0)}ms  script ${d('ScriptDuration')}s  style ${d('RecalcStyleDuration')}s  layout ${d('LayoutDuration')}s`);
    await ctx.close();
  }
  await browser.close(); server.close();
})();
