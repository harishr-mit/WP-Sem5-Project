// The T08.12 sweep (see README.md): every role's pages in dark mode at phone and desktop width, plus basic
// accessibility: no sideways scroll, every visible form field has a label (or aria-label), every image has alt text,
// every button and link has a name. Usage: node sweep.mjs <screenshot-folder>
import { chromium } from 'playwright';
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';

const PORTAL = process.env.PORTAL_URL ?? 'http://localhost:8090';
const CHANNEL = process.env.PW_CHANNEL ?? 'msedge';
const OUT = process.argv[2];
if (!OUT) { console.error('usage: node sweep.mjs <screenshot-folder>'); process.exit(2); }
mkdirSync(OUT, { recursive: true });
let failed = 0;
const check = (name, ok, detail = '') => { if (!ok) failed++; if (!ok || process.env.VERBOSE) console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  (' + detail + ')' : ''}`); };
let checks = 0;

const tours = [
    [null, ['/login', '/signup', '/events', '/check-email']],
    ['2026509001@student.annauniv.edu', ['/events', '/my/registrations', '/my/passes', '/my/stamps', '/my/profile']],
    ['organizer.a@mitpass.test', ['/organizer/events', '/organizer/events/new']],
    ['volunteer@mitpass.test', ['/scan']],
    ['admin@mitpass.test', ['/admin/users', '/admin/health', '/admin/keys']],
];

const browser = await chromium.launch(CHANNEL ? { channel: CHANNEL } : {});
for (const [width, label] of [[360, 'phone'], [1280, 'desktop']]) {
    for (const [email, pages] of tours) {
        const ctx = await browser.newContext({ viewport: { width, height: 800 }, colorScheme: 'dark', ignoreHTTPSErrors: true });
        const p = await ctx.newPage();
        if (email) {
            await p.goto(PORTAL + '/login');
            await p.fill('#email', email);
            await p.fill('#password', 'Passw0rd!');
            await Promise.all([p.waitForURL('**/events'), p.click('form.card button[type=submit]')]);
        }
        for (const path of pages) {
            await p.goto(PORTAL + path);
            const r = await p.evaluate(() => {
                const visible = (el) => !!(el.offsetWidth || el.offsetHeight || el.getClientRects().length);
                const unnamedFields = [...document.querySelectorAll('input:not([type=hidden]), select, textarea')]
                    .filter(visible)
                    .filter((el) => !(el.labels && el.labels.length) && !el.getAttribute('aria-label') && !el.getAttribute('aria-labelledby'))
                    .map((el) => el.name || el.id || el.type);
                const noAlt = [...document.querySelectorAll('img')].filter((i) => !i.hasAttribute('alt')).map((i) => i.src);
                const unnamedButtons = [...document.querySelectorAll('button, a[href]')].filter(visible)
                    .filter((b) => !(b.textContent || '').trim() && !b.getAttribute('aria-label') && !b.getAttribute('title'))
                    .map((b) => b.outerHTML.slice(0, 60));
                return {
                    wide: document.documentElement.scrollWidth - window.innerWidth,
                    dark: getComputedStyle(document.body).backgroundColor,
                    unnamedFields, noAlt, unnamedButtons,
                };
            });
            const name = `${label} ${path}`;
            checks += 4;
            check(`${name}: no sideways scroll`, r.wide <= 1, `${r.wide}px`);
            check(`${name}: dark background`, /rgb\((1[0-9]|2[0-9]|[0-9]),/.test(r.dark), r.dark);
            check(`${name}: every field has a label`, r.unnamedFields.length === 0, r.unnamedFields.join(', '));
            check(`${name}: images have alt, buttons/links a name`, r.noAlt.length === 0 && r.unnamedButtons.length === 0,
                JSON.stringify([...r.noAlt, ...r.unnamedButtons]).slice(0, 200));
            await p.screenshot({ path: join(OUT, `${label}-${(email ?? 'anon').split('@')[0]}${path.replace(/\//g, '_')}.png`), fullPage: true });
        }
        await ctx.close();
    }
}
await browser.close();
console.log(`\n${checks - failed}/${checks} checks passed; screenshots in ${OUT}`);
process.exit(failed ? 1 : 0);
