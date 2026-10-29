// Real browser geometry regressions for demand-based AI checking.
module.exports = async function demandTests({ browser, base, filter, check }) {
  async function makePage() {
    const p = await browser.newPage({ viewport: { width: 800, height: 500 } });
    await p.addInitScript(() => {
      window.__demand = [];
      window.SafeTubeAndroid = {
        config: () => JSON.stringify({ hideShorts: true }),
        submit: (json) => window.__demand.push(...JSON.parse(json)),
      };
    });
    await p.addInitScript(filter);
    await p.goto(base + '/demand');
    await p.waitForTimeout(220);
    return p;
  }
  const asked = (p, id) => p.evaluate((id) => window.__demand.filter((v) => v.id === id).length, id);
  const hidden = (p, sel) => p.$eval(sel, (el) => getComputedStyle(el).visibility === 'hidden');
  const page = await makePage();
  check('viewport demand checks visible cards and cards a few screens ahead', await asked(page, 'WHEELSBUS01') === 1 && await asked(page, 'NEARVIDEO01') === 1 && await asked(page, 'RESIZE00001') === 1);
  check('boxless wrapper keeps a measurable visible-position link hidden until checked', await page.$eval('#contents-wrapper', (el) => el.getBoundingClientRect().width === 0) && await page.$eval('#contents-link', (el) => el.getBoundingClientRect().width > 0 && getComputedStyle(el).visibility === 'hidden'));
  check('display contents wrapper submits its own visible link exactly once', await asked(page, 'CONTENTS001') === 1);
  check('display none wrapper with no visible child makes no AI request', await asked(page, 'NOLAYOUT001') === 0);
  await page.evaluate(() => window.__safetube.apply({ CONTENTS001: 'allow' }));
  check('boxless wrapper and link appear only after their own decision', !await hidden(page, '#contents-link'));
  check('cards and links beyond the look-ahead make no AI submissions', await asked(page, 'FARVIDEO001') === 0 && await asked(page, 'LOOSE000001') === 0);
  check('far-offscreen cards remain pending and hidden with measurable layout', await page.$eval('#far', (el) => el.getAttribute('data-st') === 'pending' && getComputedStyle(el).visibility === 'hidden' && el.getBoundingClientRect().height > 0));
  check('far-offscreen loose video links stay hidden', await hidden(page, '#far-link'));
  check('synthetic tap cannot bypass demand checking of an offscreen link', await page.$eval('#far-link', (el) => {
    const ev = new MouseEvent('click', { bubbles: true, cancelable: true }); el.dispatchEvent(ev); return ev.defaultPrevented;
  }));
  await page.evaluate(() => window.__safetube.apply({ WHEELSBUS01: 'allow' }));
  await page.evaluate(() => window.scrollTo(0, 3800));
  await page.waitForTimeout(260);
  check('scroll brings an offscreen card into AI demand without checking distant loose links', await asked(page, 'FARVIDEO001') === 1 && await asked(page, 'LOOSE000001') === 0);
  check('scrolling into view does not reveal a card before its own decision', await hidden(page, '#far'));
  await page.evaluate(() => window.__safetube.apply({ FARVIDEO001: 'allow' }));
  check('the newly demanded card appears after its own allow decision', !await hidden(page, '#far'));
  await page.evaluate(() => {
    const card = document.querySelector('#far');
    card.style.top = '6500px';
    card.querySelector('a').href = '/watch?v=REUSE000001';
    card.querySelector('h3').textContent = 'A different video from the same channel';
  });
  await page.waitForTimeout(220);
  check('recycled allowed card becomes hidden while its new offscreen video stays unsubmitted', await hidden(page, '#far') && await asked(page, 'REUSE000001') === 0);
  await page.evaluate(() => window.scrollTo(0, 6300));
  await page.waitForTimeout(260);
  check('recycled card from an allowed channel still needs its own check when scrolled near', await asked(page, 'REUSE000001') === 1 && await hidden(page, '#far'));
  await page.evaluate(() => window.scrollTo(0, 4600));
  await page.waitForTimeout(260);
  check('loose link submits when scrolled near and remains hidden until checked', await asked(page, 'LOOSE000001') === 1 && await hidden(page, '#far-link'));
  await page.evaluate(() => { for (let i = 0; i < 20; i++) { dispatchEvent(new Event('scroll')); dispatchEvent(new Event('resize')); } });
  await page.waitForTimeout(260);
  check('scroll and resize bursts do not duplicate in-flight submissions', await asked(page, 'LOOSE000001') === 1);
  await page.close();

  const resized = await makePage();
  await resized.setViewportSize({ width: 800, height: 1000 });
  await resized.waitForTimeout(260);
  check('resize activates newly near-screen content while farther cards stay unsubmitted', await asked(resized, 'RESIZE00001') === 1 && await asked(resized, 'FARVIDEO001') === 0);
  await resized.evaluate(() => {
    const card = document.createElement('ytm-rich-item-renderer');
    card.id = 'fast-scroll'; card.style.top = '40px';
    card.innerHTML = '<a href="/watch?v=FASTSCROLL1"><h3>Briefly near</h3></a>';
    document.body.appendChild(card);
    window.__safetube.scan();
    card.style.top = '7500px';
  });
  await resized.waitForTimeout(240);
  check('card moved offscreen during batching stays hidden without an AI request', await asked(resized, 'FASTSCROLL1') === 0 && await hidden(resized, '#fast-scroll'));
  await resized.evaluate(() => history.pushState({}, '', '/watch?v=DIRECT00001'));
  await resized.waitForTimeout(100);
  check('direct watch submits even with the player far below the viewport and hidden', await asked(resized, 'DIRECT00001') === 1 && await hidden(resized, '#player'));
  await resized.evaluate(() => window.__safetube.apply({ DIRECT00001: 'allow' }));
  await resized.evaluate(() => history.pushState({}, '', '/watch?v=SPAWATCH001'));
  await resized.waitForTimeout(100);
  check('SPA watch navigation checks the new id despite previous allow and offscreen player', await asked(resized, 'SPAWATCH001') === 1 && await hidden(resized, '#player'));
  const geometry = await resized.$eval('#far', (el) => ({ top: el.getBoundingClientRect().top, height: el.getBoundingClientRect().height }));
  await resized.evaluate(() => { history.pushState({}, '', '/'); window.__safetube.setAiUnavailable(true); window.__safetube.scan(); });
  await resized.waitForTimeout(220);
  const lockedGeometry = await resized.$eval('#far', (el) => ({ top: el.getBoundingClientRect().top, height: el.getBoundingClientRect().height }));
  check('inherited outage visibility preserves card layout and does not create offscreen demand', geometry.top === lockedGeometry.top && geometry.height === lockedGeometry.height && await asked(resized, 'FARVIDEO001') === 0 && await hidden(resized, '#far'));
  await resized.close();
};
