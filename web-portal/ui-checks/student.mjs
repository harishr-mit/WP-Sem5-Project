// The student journey in a real browser (see README.md). Usage: node student.mjs <screenshot-folder>
import { chromium } from 'playwright';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const PORTAL = process.env.PORTAL_URL ?? 'http://localhost:8090';
const GATEWAY = process.env.GATEWAY_URL ?? 'http://localhost:8080';
const CHANNEL = process.env.PW_CHANNEL ?? 'msedge';
const OUT = process.argv[2];
if (!OUT) {
    console.error('usage: node student.mjs <screenshot-folder>');
    process.exit(2);
}
mkdirSync(OUT, { recursive: true });

const report = { checks: [], consoleErrors: [], badResponses: [], screenshots: [] };
function check(name, ok, detail = '') {
    report.checks.push({ name, ok, detail });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  (' + detail + ')' : ''}`);
}

// ---- demo data through the API (organizer A): an event starting in an hour, so the live code is unlocked -------
async function api(method, path, token, body) {
    const res = await fetch(GATEWAY + path, {
        method,
        headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) },
        body: body ? JSON.stringify(body) : undefined,
    });
    if (!res.ok) {
        throw new Error(`${method} ${path} -> ${res.status} ${await res.text()}`);
    }
    return res.status === 204 ? null : res.json();
}
const organizer = (await api('POST', '/api/auth/login', null,
    { email: 'organizer.a@mitpass.test', password: 'Passw0rd!' })).accessToken;
const code = 'UI' + Date.now().toString(36).toUpperCase().slice(-6);
const now = Date.now();
const iso = (ms) => new Date(ms).toISOString();
const event = await api('POST', '/api/events', organizer, {
    code, title: 'UI Check Night', categoryId: 1, venueId: 1, capacity: 40,
    description: 'An event made by the UI check.\nTwo lines of description.',
    startsAt: iso(now + 60 * 60_000), endsAt: iso(now + 4 * 60 * 60_000),
    registrationClosesAt: iso(now + 50 * 60_000), passColor: '#1D4E89',
});
await api('POST', `/api/events/${event.id}/transitions`, organizer, { to: 'PUBLISHED' });
await api('POST', `/api/events/${event.id}/transitions`, organizer, { to: 'REGISTRATION_OPEN' });
check('demo event created and open', true, `${code} #${event.id}`);

// ---- browser ---------------------------------------------------------------------------------------------------
const browser = await chromium.launch(CHANNEL ? { channel: CHANNEL } : {});
const PHONE = { width: 360, height: 780 };
const DESKTOP = { width: 1280, height: 900 };

function watch(page, label) {
    page.on('console', (m) => {
        // the deliberate not-found page logs its own 404 document load
        if (m.type() === 'error' && !page.url().includes('/events/999999')) {
            report.consoleErrors.push({ label, url: page.url(), text: m.text() });
        }
    });
    page.on('pageerror', (e) => report.consoleErrors.push({ label, url: page.url(), text: String(e) }));
    page.on('response', (r) => {
        const s = r.status();
        // 404 is expected on the deliberate not-found page; 286 is HTMX "stop polling"
        if (s >= 400 && !r.url().includes('/events/999999')) report.badResponses.push({ label, url: r.url(), status: s });
    });
}
async function shot(page, name) {
    const file = join(OUT, name + '.png');
    await page.screenshot({ path: file, fullPage: true });
    report.screenshots.push(file);
}
async function noHorizontalScroll(page, name) {
    const wide = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    check(`no horizontal scroll: ${name}`, wide <= 1, `${wide}px`);
}

// Phone, light: the whole journey
// ignoreHTTPSErrors: the lan profile's self-signed certificate (PORTAL_URL=https://localhost:8443)
const phone = await browser.newContext({ viewport: PHONE, deviceScaleFactor: 2, colorScheme: 'light', locale: 'en-IN', ignoreHTTPSErrors: true });
const p = await phone.newPage();
watch(p, 'phone-light');

await p.goto(PORTAL + '/login');
await shot(p, '01-login-phone');
await p.fill('#email', '2026509001@student.annauniv.edu');
await p.fill('#password', 'Passw0rd!');
await Promise.all([p.waitForURL('**/events'), p.click('form.card button[type=submit]')]);
check('login lands on Explore', p.url().endsWith('/events'));
await shot(p, '02-explore-phone');
await noHorizontalScroll(p, 'explore');

// Live search: typing swaps #results without a page load, and the URL follows
const marker = await p.evaluate(() => (window.__mark = Math.random()));
await p.fill('#q', 'UI Check');
await p.waitForFunction(() => location.search.includes('q=UI'), null, { timeout: 5000 });
await p.waitForSelector(`.event-card:has-text("${code}")`, { timeout: 5000 });
check('live search swapped results in place', (await p.evaluate(() => window.__mark)) === marker);
await shot(p, '03-search-phone');

await Promise.all([p.waitForURL(`**/events/${event.id}`), p.click(`.event-card:has-text("${code}")`)]);
await shot(p, '04-event-phone');
await noHorizontalScroll(p, 'event');

await Promise.all([p.waitForURL(`**/events/${event.id}/registered`), p.click('#my-registration button[type=submit]')]);
await shot(p, '05-youre-in-waiting-phone');
await p.waitForSelector('#pass-step >> text=Pass issued and signed', { timeout: 15000 });
check('"You\'re in" showed the pass without a reload', true);
await shot(p, '06-youre-in-phone');

await Promise.all([p.waitForURL('**/my/passes/*'), p.click('#pass-step a.btn')]);
await p.waitForSelector('#live-qr', { timeout: 5000 });
const qrLoaded = await p.evaluate(() => {
    const img = document.getElementById('live-qr');
    return img.complete && img.naturalWidth > 0;
});
check('live QR (SVG) loaded', qrLoaded);
const t0 = Number(await p.textContent('#qr-left'));
await p.waitForTimeout(2300);
const t1 = Number(await p.textContent('#qr-left'));
check('countdown runs', t1 < t0 && t1 > 0, `${t0} -> ${t1}`);
await shot(p, '07-pass-phone');
await noHorizontalScroll(p, 'pass');
const cardSide = await p.evaluate(() => document.getElementById('live-qr').getBoundingClientRect().width);
check('the code is drawn large on the card', cardSide >= 250, `${Math.round(cardSide)}px at ${PHONE.width}px`);

// Tap the code: full screen on white for the scanner (two-phone demo, 2026-10-19), tap again / Escape closes
await p.click('[data-qr-zoom]');
await p.waitForFunction(() => document.getElementById('live-qr-zoom').naturalWidth > 0, null, { timeout: 5000 });
const zoomed = await p.evaluate(() => {
    const view = document.getElementById('qr-zoom-view');
    const r = document.getElementById('live-qr-zoom').getBoundingClientRect();
    return { on: !view.hidden, side: r.width, bg: getComputedStyle(view).backgroundColor,
        pressed: document.querySelector('[data-qr-zoom]').getAttribute('aria-pressed') };
});
check('tapping the code shows it full screen on white', zoomed.on && zoomed.side >= PHONE.width * 0.9 && zoomed.bg === 'rgb(255, 255, 255)',
    `${Math.round(zoomed.side)}px, ${zoomed.bg}, aria-pressed=${zoomed.pressed}`);
await shot(p, '07b-pass-zoomed-phone');
await p.click('#qr-zoom-view');
check('tapping again closes it', await p.evaluate(() => document.getElementById('qr-zoom-view').hidden));
await p.click('[data-qr-zoom]');
await p.keyboard.press('Escape');
check('Escape closes it too', await p.evaluate(() => document.getElementById('qr-zoom-view').hidden));

for (const [path, name] of [['/my/passes', '08-wallet'], ['/my/registrations', '09-registrations'],
    ['/my/stamps', '10-stamps'], ['/my/profile', '11-profile'], ['/events/999999', '12-not-found']]) {
    await p.goto(PORTAL + path);
    await shot(p, name + '-phone');
    await noHorizontalScroll(p, path);
}
// The cancel dialog opens (and is closed again without cancelling)
await p.goto(PORTAL + '/my/registrations');
const opener = p.locator('[data-open-dialog^="cancel-"]').first();
if (await opener.count()) {
    await opener.click();
    check('cancel asks in a dialog first', await p.locator('dialog[open]').isVisible());
    await shot(p, '13-cancel-dialog-phone');
    await p.click('dialog[open] [data-close-dialog]');
}
const state = await phone.storageState();

// Dark mode (OS preference) and desktop width, logged in as the same session
for (const [label, opts] of [['dark-phone', { viewport: PHONE, colorScheme: 'dark' }],
    ['desktop', { viewport: DESKTOP, colorScheme: 'light' }], ['dark-desktop', { viewport: DESKTOP, colorScheme: 'dark' }]]) {
    const ctx = await browser.newContext({ ...opts, storageState: state, deviceScaleFactor: 1, ignoreHTTPSErrors: true });
    const page = await ctx.newPage();
    watch(page, label);
    for (const [path, name] of [['/events', 'explore'], [`/events/${event.id}`, 'event'],
        ['/my/passes', 'wallet'], ['/my/stamps', 'stamps']]) {
        await page.goto(PORTAL + path);
        await shot(page, `${name}-${label}`);
        await noHorizontalScroll(page, `${path} ${label}`);
    }
    await page.goto(PORTAL + '/my/passes');
    const pass = page.locator('a.pass-tile').first();
    if (await pass.count()) {
        await pass.click();
        await page.waitForSelector('.pass');
        await shot(page, `pass-${label}`);
    }
    await ctx.close();
}

// Tamil
const ta = await browser.newContext({ viewport: PHONE, storageState: state, deviceScaleFactor: 2, ignoreHTTPSErrors: true });
const tp = await ta.newPage();
watch(tp, 'tamil');
await tp.goto(PORTAL + '/events?lang=ta');
await shot(tp, 'explore-tamil-phone');
await noHorizontalScroll(tp, 'explore tamil');
await ta.close();

await browser.close();
check('no console errors', report.consoleErrors.length === 0, JSON.stringify(report.consoleErrors.slice(0, 3)));
check('no failed requests', report.badResponses.length === 0, JSON.stringify(report.badResponses.slice(0, 3)));
writeFileSync(join(OUT, 'report.json'), JSON.stringify(report, null, 2));
const failed = report.checks.filter((c) => !c.ok).length;
console.log(`\n${report.checks.length - failed}/${report.checks.length} checks passed; ${report.screenshots.length} screenshots in ${OUT}`);
process.exit(failed ? 1 : 0);
