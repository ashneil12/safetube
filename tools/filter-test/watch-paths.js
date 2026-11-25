// Every playable YouTube path must stay behind the same native decision boundary.
module.exports = async function watchPathTests({ browser, base, filter, check }) {
  for (const [path, id] of [['/embed/', 'EMBED000001'], ['/v/', 'LEGACY00001'], ['/watch/', 'PATHWATCH01']]) {
    const page = await browser.newPage();
    await page.addInitScript(() => {
      window.__pathSubmitted = [];
      window.__pathPaused = 0;
      HTMLMediaElement.prototype.pause = function () { window.__pathPaused++; };
      window.SafeTubeAndroid = {
        config: () => JSON.stringify({}),
        submit: (json) => window.__pathSubmitted.push(...JSON.parse(json)),
      };
    });
    await page.addInitScript(filter);
    await page.goto(base + path + id);
    await page.waitForTimeout(120);
    check(`${path} direct player submits its own video and stays hidden pending approval`, await page.evaluate((id) =>
      window.__pathSubmitted.some((v) => v.id === id) && document.documentElement.getAttribute('data-st-watch') === 'pending' && getComputedStyle(document.querySelector('#player')).visibility === 'hidden', id));
    await page.evaluate(() => { window.__pathPaused = 0; document.querySelector('video').dispatchEvent(new Event('play', { bubbles: true })); });
    check(`${path} unchecked playback is paused`, await page.evaluate(() => window.__pathPaused > 0));
    await page.evaluate((id) => window.__safetube.apply({ [id]: 'allow' }), id);
    check(`${path} player appears only after its own approval`, await page.evaluate(() => document.documentElement.getAttribute('data-st-watch') === 'allow' && getComputedStyle(document.querySelector('#player')).visibility === 'visible'));
    const next = 'NEXTWATCH01';
    await page.evaluate((path) => history.pushState({}, '', path + 'NEXTWATCH01'), path);
    await page.waitForTimeout(100);
    check(`${path} SPA navigation checks the new video and hides the previously allowed player`, await page.evaluate((id) => window.__pathSubmitted.some((v) => v.id === id) && document.documentElement.getAttribute('data-st-watch') === 'pending' && getComputedStyle(document.querySelector('#player')).visibility === 'hidden', next));
    await page.evaluate((id) => window.__safetube.apply({ [id]: 'block' }), next);
    check(`${path} blocked video keeps its player hidden`, await page.evaluate(() => document.documentElement.getAttribute('data-st-watch') === 'block' && getComputedStyle(document.querySelector('#player')).visibility === 'hidden'));
    await page.close();
  }

  const links = await browser.newPage();
  await links.addInitScript(() => {
    window.SafeTubeAndroid = { config: () => '{}', submit: () => {} };
  });
  await links.addInitScript(filter);
  await links.goto(base + '/watch?isolated=1');
  await links.evaluate(() => {
    for (const [type, id] of [['embed', 'EMBED000001'], ['v', 'LEGACY00001']]) {
      const a = document.createElement('a'); a.id = 'path-' + type; a.href = '/' + type + '/' + id; a.textContent = 'Open alternate player'; document.body.appendChild(a);
    }
  });
  await links.waitForTimeout(120);
  check('unchecked embed and legacy-player links are hidden and cannot bypass approval by synthetic clicks', await links.evaluate(() => ['embed', 'v'].every((type) => {
    const a = document.getElementById('path-' + type);
    const ev = new MouseEvent('click', { bubbles: true, cancelable: true }); a.dispatchEvent(ev);
    return getComputedStyle(a).visibility === 'hidden' && ev.defaultPrevented;
  })));
  await links.close();
};
