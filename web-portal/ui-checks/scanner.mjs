// The volunteer's scanner with a camera, end to end (see README.md). Usage: node scanner.mjs <screenshot-folder>
// Makes an event that goes LIVE within a minute, registers Priya, assigns the volunteer to its gate, then feeds
// Priya's real live QR to the browser as its camera (Chromium's fake video capture) and checks the scanner reads it:
// green "Admitted", then amber "Already in" for the same code, then the manual tab.
import { chromium } from 'playwright';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { decodePng, writeY4m } from './qr-video.mjs';

const PORTAL = process.env.PORTAL_URL ?? 'http://localhost:8090';
const GATEWAY = process.env.GATEWAY_URL ?? 'http://localhost:8080';
const CHANNEL = process.env.PW_CHANNEL ?? 'msedge';
const OUT = process.argv[2];
if (!OUT) {
    console.error('usage: node scanner.mjs <screenshot-folder>');
    process.exit(2);
}
mkdirSync(OUT, { recursive: true });
const report = { checks: [], consoleErrors: [] };
function check(name, ok, detail = '') {
    report.checks.push({ name, ok, detail });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  (' + detail + ')' : ''}`);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function api(method, path, token, body, accept) {
    const res = await fetch(GATEWAY + path, {
        method,
        headers: {
            ...(body ? { 'Content-Type': 'application/json' } : {}),
            ...(token ? { Authorization: 'Bearer ' + token } : {}),
            ...(accept ? { Accept: accept } : {}),
        },
        body: body ? JSON.stringify(body) : undefined,
    });
    if (!res.ok) throw new Error(`${method} ${path} -> ${res.status} ${await res.text()}`);
    if (accept && accept.startsWith('image/')) return Buffer.from(await res.arrayBuffer());
    return res.status === 204 || res.headers.get('content-length') === '0' ? null : res.json().catch(() => null);
}
const login = async (email) => (await api('POST', '/api/auth/login', null, { email, password: 'Passw0rd!' })).accessToken;

// ---- an event that is LIVE now, with a gate and the volunteer on it ---------------------------------------------
const organizer = await login('organizer.a@mitpass.test');
const priya = await login('2026509001@student.annauniv.edu');
const now = Date.now();
const iso = (ms) => new Date(ms).toISOString();
const code = 'SCN' + Date.now().toString(36).toUpperCase().slice(-6);
const event = await api('POST', '/api/events', organizer, {
    code, title: 'Scanner Check', categoryId: 1, venueId: 1, capacity: 20,
    startsAt: iso(now + 20 * 60_000), endsAt: iso(now + 3 * 60 * 60_000), registrationClosesAt: iso(now + 15 * 60_000),
});
await api('POST', `/api/events/${event.id}/transitions`, organizer, { to: 'PUBLISHED' });
await api('POST', `/api/events/${event.id}/transitions`, organizer, { to: 'REGISTRATION_OPEN' });
const gate = await api('POST', `/api/events/${event.id}/gates`, organizer, { code: 'MAIN', name: 'Main Gate' });
await api('POST', `/api/events/${event.id}/volunteers`, organizer, { email: 'volunteer@mitpass.test', gateId: gate.id });
await api('POST', '/api/registrations', priya, { eventId: event.id });
// The scheduler starts an event only once registration is closed; close it now instead of waiting 15 minutes.
await api('POST', `/api/events/${event.id}/transitions`, organizer, { to: 'REGISTRATION_CLOSED' });
check('event, gate, volunteer and registration set up', true, `${code} #${event.id}`);

let pass = null;
for (let i = 0; i < 30 && !pass; i++) {
    const mine = await api('GET', '/api/passes/me?size=50', priya);
    pass = mine.content.find((p) => p.eventId === event.id && p.status === 'ACTIVE') ?? null;
    if (!pass) await sleep(1000);
}
check('Priya has an ACTIVE pass', !!pass, pass?.passNumber);

let status = '';
for (let i = 0; i < 60 && status !== 'LIVE'; i++) {
    status = (await api('GET', `/api/events/${event.id}`, organizer)).status;
    if (status !== 'LIVE') await sleep(3000);
}
check('the scheduler made the event LIVE', status === 'LIVE', status);

// ---- Priya's live code as the camera's picture (valid 45 s: fetched just before the browser starts) --------------
const png = await api('GET', `/api/passes/${pass.id}/qr/live`, priya, null, 'image/png');
const video = resolve(OUT, 'live-qr.y4m');
writeY4m(decodePng(png), video);

const browser = await chromium.launch({
    ...(CHANNEL ? { channel: CHANNEL } : {}),
    args: ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream', `--use-file-for-fake-video-capture=${video}`],
});
const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, permissions: ['camera'] });
const p = await ctx.newPage();
p.on('console', (m) => { if (m.type() === 'error') report.consoleErrors.push(m.text()); });
p.on('pageerror', (e) => report.consoleErrors.push(String(e)));
const triggers = [];
p.on('response', (r) => {
    if (r.url().includes('/scan/submit') || r.url().includes('/scan/check')) {
        triggers.push(r.headers()['hx-trigger'] ?? '');
    }
});

await p.goto(PORTAL + '/login');
await p.fill('#email', 'volunteer@mitpass.test');
await p.fill('#password', 'Passw0rd!');
await Promise.all([p.waitForURL('**/events'), p.click('form.card button[type=submit]')]);
await p.goto(PORTAL + '/scan');
await p.selectOption('#gate', String(gate.id));
await shot('01-scanner');

const card = p.locator('#scan-result .result');
await card.waitFor({ timeout: 20000 });
const first = await card.getAttribute('class');
check('the camera read the live QR: green Admitted', first.includes('result--green') && (await card.textContent()).includes('Admitted'), first);
check('the answer carried the flash colour', triggers.some((t) => t.includes('"green"')), triggers[0]);
await shot('02-admitted');

// Scan next: the same code is still in front of the camera → amber "Already in"
await p.click('[data-scan-next]');
await p.waitForSelector('#scan-result .result--amber', { timeout: 20000 });
check('the same pass again: amber Already in', (await card.textContent()).includes('Already in'));
await shot('03-already-in');

// Manual tab: typed number → visual check → confirm
await p.click('[data-scan-next]');
await p.click('[data-tab=manual]');
await p.fill('#code', pass.passNumber);
await p.click('#manual-panel button[type=submit]');
await p.waitForSelector('#scan-result >> text=Is this the person?', { timeout: 10000 });
check('manual entry shows the name first', (await p.textContent('#scan-result')).includes('Priya'));
await shot('04-manual-check');
await p.click('#scan-result button[type=submit]');
await p.waitForSelector('#scan-result .result', { timeout: 10000 });
check('confirming the manual entry answers too', (await card.textContent()).includes('Already in'));
const wide = await p.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
check('no horizontal scroll on the phone', wide <= 1, `${wide}px`);
check('counter counted the green scan', (await p.textContent('#scan-count')).trim() === '1');
await shot('05-manual-result');

async function shot(name) {
    await p.waitForTimeout(700); // let the result flash fade (0.5 s) so the screenshot shows the page itself
    await p.screenshot({ path: join(OUT, name + '.png'), fullPage: true });
}
await browser.close();
check('no console errors', report.consoleErrors.length === 0, JSON.stringify(report.consoleErrors.slice(0, 3)));
writeFileSync(join(OUT, 'report.json'), JSON.stringify(report, null, 2));
const failed = report.checks.filter((c) => !c.ok).length;
console.log(`\n${report.checks.length - failed}/${report.checks.length} checks passed; screenshots in ${OUT}`);
process.exit(failed ? 1 : 0);
