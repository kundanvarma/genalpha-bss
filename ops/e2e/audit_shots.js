const { chromium } = require('playwright');
const SP = '/private/tmp/claude-501/-Users-kundanverma-Documents-projects-bssproject-bss-java/61678155-dc15-40ed-98c7-c58fcd6f23e6/scratchpad';
(async () => {
  const b = await chromium.launch();
  const shoot = async (ctx, url, name, wait = 3000, full = true) => {
    const p = await ctx.newPage();
    await p.goto(url); await p.waitForTimeout(wait);
    await p.screenshot({ path: `${SP}/${name}.png`, fullPage: full });
    console.log('·', name);
    await p.close();
  };
  const login = async (viewport, user, pass, entry, client) => {
    const ctx = await b.newContext({ viewport });
    const p = await ctx.newPage();
    await p.goto(entry);
    const si = p.locator('text=/Sign in|Logg inn/').first();
    if (await si.count()) await si.click().catch(()=>{});
    await p.waitForSelector('input[name="username"]', { timeout: 20000 });
    await p.fill('input[name="username"]', user);
    await p.fill('input[name="password"]', pass);
    await p.click('input[type="submit"], button[type="submit"]');
    await p.waitForTimeout(4000);
    await p.close();
    return ctx;
  };
  // customer portal — wilma (clean family member)
  const D = { width: 1440, height: 900 }, M = { width: 390, height: 844 };
  const cw = await login(D, 'wilma@family.example', 'wilma', 'http://localhost:8080/shop/');
  await shoot(cw, 'http://localhost:8080/shop/services', 'aud-my-desktop');
  await shoot(cw, 'http://localhost:8080/shop/family', 'aud-family-desktop');
  await shoot(cw, 'http://localhost:8080/shop/support', 'aud-support-desktop');
  await cw.close();
  const cm = await login(M, 'wilma@family.example', 'wilma', 'http://localhost:8080/shop/');
  await shoot(cm, 'http://localhost:8080/shop/services', 'aud-my-mobile');
  await shoot(cm, 'http://localhost:8080/shop/', 'aud-shop-mobile');
  await cm.close();
  // B2B console — birgit on nova
  const bd = await b.newContext({ viewport: D });
  const bp = await bd.newPage();
  await bp.goto('http://biz.nova.localhost:8080/biz/');
  await bp.waitForSelector('input[name="username"]', { timeout: 20000 });
  await bp.fill('input[name="username"]', 'birgit@fjellheim.no');
  await bp.fill('input[name="password"]', 'birgit');
  await bp.click('input[type="submit"], button[type="submit"]');
  await bp.waitForSelector('#main:not([hidden])', { timeout: 20000 });
  await bp.waitForTimeout(4000);
  await bp.screenshot({ path: `${SP}/aud-biz-desktop.png`, fullPage: true });
  console.log('· aud-biz-desktop');
  await bp.setViewportSize(M);
  await bp.waitForTimeout(1500);
  await bp.screenshot({ path: `${SP}/aud-biz-mobile.png`, fullPage: true });
  console.log('· aud-biz-mobile');
  await bd.close();
  await b.close();
  console.log('AUDIT SHOTS DONE');
})().catch(e => { console.error('ERR', e.message.slice(0,150)); process.exit(1); });
