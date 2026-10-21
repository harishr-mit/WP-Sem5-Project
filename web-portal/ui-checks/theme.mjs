// The theme switch (owner bug, 2026-10-21): on a dark system with no saved choice the page is dark, so the switch
// must offer light (sun); one click → light (moon); another → dark (sun). And the active sub-tab is gold in system
// dark, as with an explicit dark choice. Usage: node theme.mjs <screenshot-folder>   (needs the portal on :8090)
import { chromium } from 'playwright';
import { join } from 'node:path';
import { mkdirSync } from 'node:fs';

const PORTAL = process.env.PORTAL_URL ?? 'http://localhost:8090';
const OUT = process.argv[2] ?? '.';
mkdirSync(OUT, { recursive: true });
let failed = 0;
const check = (name, ok, detail = '') => {
    if (!ok) failed++;
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  (' + detail + ')' : ''}`);
};
const SUN = '☀';
const MOON = '☾';

const browser = await chromium.launch(process.env.PW_CHANNEL === '' ? {} : { channel: process.env.PW_CHANNEL ?? 'msedge' });
const ctx = await browser.newContext({ colorScheme: 'dark', viewport: { width: 1280, height: 800 } });
const p = await ctx.newPage();
const errors = [];
p.on('pageerror', (e) => errors.push(String(e)));
await p.goto(PORTAL + '/login');
await p.fill('#email', 'organizer.a@mitpass.test');
await p.fill('#password', 'Passw0rd!');
await Promise.all([p.waitForURL('**/events'), p.click('form.card button[type=submit]')]);

const toggle = 'form[action$="/preferences/theme"] button';
const state = async () => ({
    theme: await p.evaluate(() => document.documentElement.getAttribute('data-theme')),
    icon: (await p.textContent(toggle)).trim(),
});
async function click() {
    await Promise.all([p.waitForNavigation(), p.click(toggle)]);
}

let s = await state();
check('system dark, no choice: the switch offers light', s.theme === null && s.icon === SUN, JSON.stringify(s));
await click();
s = await state();
check('one click: light, the switch offers dark', s.theme === 'light' && s.icon === MOON, JSON.stringify(s));
await click();
s = await state();
check('another click: dark, the switch offers light', s.theme === 'dark' && s.icon === SUN, JSON.stringify(s));

// back to "follow the system" (no cookie), then the active sub-tab of an event page
await ctx.clearCookies({ name: 'mitpass-theme' });
await p.goto(PORTAL + '/organizer/events');
const edit = await p.getAttribute('a[href*="/organizer/events/"][href$="/edit"]', 'href');
await p.goto(PORTAL + edit);
const colors = await p.evaluate(() => {
    const probe = document.createElement('span');
    probe.style.color = 'var(--gold)';
    document.body.appendChild(probe);
    return [getComputedStyle(document.querySelector('.subnav a[aria-current="page"]')).color, getComputedStyle(probe).color];
});
check('system dark: the active sub-tab is gold', colors[0] === colors[1], colors.join(' vs '));
await p.screenshot({ path: join(OUT, 'theme-system-dark.png') });

check('no page errors', errors.length === 0, errors.join(' | '));
await browser.close();
process.exit(failed ? 1 : 0);
