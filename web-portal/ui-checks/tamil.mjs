// The portal in Tamil (D15, T08.11): key pages at phone width with the language cookie set. Checks there is no
// missing-key marker (??…??), no horizontal scroll (Tamil words are longer), and keeps screenshots for the owner's
// review of the wording (log.md T34). Usage: node tamil.mjs <screenshot-folder>
import { chromium } from 'playwright';
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';

const PORTAL = process.env.PORTAL_URL ?? 'http://localhost:8090';
const CHANNEL = process.env.PW_CHANNEL ?? 'msedge';
const OUT = process.argv[2];
if (!OUT) { console.error('usage: node tamil.mjs <screenshot-folder>'); process.exit(2); }
mkdirSync(OUT, { recursive: true });
let failed = 0;
const check = (name, ok, detail = '') => { if (!ok) failed++; console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  (' + detail + ')' : ''}`); };

const browser = await chromium.launch(CHANNEL ? { channel: CHANNEL } : {});
async function tour(email, pages) {
    const ctx = await browser.newContext({ viewport: { width: 360, height: 780 }, deviceScaleFactor: 2 });
    await ctx.addCookies([{ name: 'mitpass-lang', value: 'ta', url: PORTAL }]);
    const p = await ctx.newPage();
    if (email) {
        await p.goto(PORTAL + '/login');
        await p.fill('#email', email);
        await p.fill('#password', 'Passw0rd!');
        await Promise.all([p.waitForURL('**/events'), p.click('form.card button[type=submit]')]);
    }
    for (const [path, name] of pages) {
        await p.goto(PORTAL + path);
        if (path === '/my/passes' && await p.locator('a.pass-tile').count()) {
            await p.locator('a.pass-tile').first().click();
            await p.waitForSelector('.pass');
        }
        const html = await p.content();
        const wide = await p.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
        check(`${name}: no missing keys, no sideways scroll`, !html.includes('??') && wide <= 1, `${wide}px`);
        await p.screenshot({ path: join(OUT, name + '.png'), fullPage: true });
    }
    await ctx.close();
}
await tour(null, [['/signup', 'signup'], ['/login', 'login']]);
await tour('2026509001@student.annauniv.edu', [['/events', 'explore'], ['/my/registrations', 'registrations'], ['/my/passes', 'pass'], ['/my/stamps', 'stamps'], ['/my/profile', 'profile']]);
await tour('organizer.a@mitpass.test', [['/organizer/events', 'my-events'], ['/organizer/events/new', 'event-form']]);
await tour('volunteer@mitpass.test', [['/scan', 'scanner']]);
await browser.close();
console.log(failed ? `\n${failed} check(s) failed` : '\nall checks passed');
process.exit(failed ? 1 : 0);
