// Phase 7 in a real browser (docs/plans/phase-7.md §1, exit criteria 1–5). Usage: node p7.mjs <screenshot-folder>
// Needs the whole stack (README "Quick start"), including notification-service, Redis and Mailpit.
//  1. Live dashboard: the organizer's Live tab updates within ~1 s of a scan, with no reload (STOMP via LiveRelay).
//  2. Emails: the pass email in Mailpit has the QR inline and the .ics; waitlisted / promoted / cancelled arrive.
//  3. Add to calendar: the event page's button downloads text/calendar.
//  4. Offline scanner: WebCrypto and Luhn on real data; offline scans are checked on the phone and queued; back online
//     they sync, and a pass checked in online meanwhile comes back DUPLICATE_OFFLINE; the dashboard counts the sync;
//     the service worker serves /scan with no network.
//  5. Dead letters: a poison message reaches notification-service's DLQ, the health page turns amber, the admin page
//     lists it and discards it.
// Screenshots at phone and desktop width, light and dark. Fails on console errors.
import { chromium } from 'playwright';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const PORTAL = process.env.PORTAL_URL ?? 'http://localhost:8090';
const GATEWAY = process.env.GATEWAY_URL ?? 'http://localhost:8080';
const MAILPIT = process.env.MAILPIT_URL ?? 'http://localhost:8025';
const RABBIT = process.env.RABBIT_URL ?? 'http://localhost:15672';
const CHANNEL = process.env.PW_CHANNEL ?? 'msedge';
const OUT = process.argv[2];
if (!OUT) {
    console.error('usage: node p7.mjs <screenshot-folder>');
    process.exit(2);
}
mkdirSync(OUT, { recursive: true });
const report = { checks: [], consoleErrors: [] };
function check(name, ok, detail = '') {
    report.checks.push({ name, ok, detail });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  (' + detail + ')' : ''}`);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const PHONE = { width: 360, height: 780 };
const DESKTOP = { width: 1280, height: 860 };

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
    return res.status === 204 || res.headers.get('content-length') === '0' ? null : res.json().catch(() => null);
}
const login = async (email) => (await api('POST', '/api/auth/login', null, { email, password: 'Passw0rd!' })).accessToken;
async function mails(query) {
    const r = await (await fetch(`${MAILPIT}/api/v1/search?query=${encodeURIComponent(query)}`)).json();
    return r.messages ?? [];
}
async function waitFor(fn, ms = 15000, every = 500) {
    const end = Date.now() + ms;
    let value;
    while (Date.now() < end) {
        value = await fn();
        if (value) return value;
        await sleep(every);
    }
    return value;
}

// ---- setup ----------------------------------------------------------------------------------------------------------
const organizer = await login('organizer.a@mitpass.test');
const priya = await login('2026509001@student.annauniv.edu');
const arun = await login('2026505014@student.annauniv.edu');
const admin = await login('admin@mitpass.test');
const now = Date.now();
const iso = (ms) => new Date(ms).toISOString();
const stamp = Date.now().toString(36).toUpperCase().slice(-5);
async function makeEvent(code, title, capacity) {
    const e = await api('POST', '/api/events', organizer, {
        code, title, categoryId: 1, venueId: 1, capacity,
        startsAt: iso(now + 20 * 60_000), endsAt: iso(now + 3 * 60 * 60_000), registrationClosesAt: iso(now + 15 * 60_000),
    });
    await api('POST', `/api/events/${e.id}/transitions`, organizer, { to: 'PUBLISHED' });
    await api('POST', `/api/events/${e.id}/transitions`, organizer, { to: 'REGISTRATION_OPEN' });
    return e;
}

// ---- 2. emails: a one-seat event (waitlisted → promoted → pass; cancelled) ----------------------------------------
const seat = await makeEvent('P7M' + stamp, 'Phase 7 Mail ' + stamp, 1);
const priyaReg = await api('POST', '/api/registrations', priya, { eventId: seat.id });
const arunReg = await api('POST', '/api/registrations', arun, { eventId: seat.id });
check('Arun is waitlisted (one seat)', arunReg.status === 'WAITLISTED', arunReg.status);
await api('POST', `/api/registrations/${priyaReg.id}/cancel`, priya, {});
const priyaMail = '2026509001@student.annauniv.edu';
const arunMail = '2026505014@student.annauniv.edu';
const subjects = async (to) => (await mails(`to:${to} subject:"${seat.title}"`)).map((m) => m.Subject);
const arunSubjects = await waitFor(async () => {
    const s = await subjects(arunMail);
    return s.some((x) => x.startsWith('Your pass for')) ? s : null;
}, 30000);
check('Arun: waitlisted, promoted, then his pass by email',
    !!arunSubjects && arunSubjects.some((s) => s.startsWith("You're on the waitlist (#1)"))
        && arunSubjects.some((s) => s.startsWith('You got a seat')), (arunSubjects ?? []).join(' | '));
const priyaSubjects = await waitFor(async () => {
    const s = await subjects(priyaMail);
    return s.some((x) => x.startsWith('Registration cancelled')) ? s : null;
}, 15000);
check('Priya: her pass, then the cancellation', !!priyaSubjects, (priyaSubjects ?? []).join(' | '));
const passMail = (await mails(`to:${arunMail} subject:"Your pass for ${seat.title}"`))[0];
const detail = passMail ? await (await fetch(`${MAILPIT}/api/v1/message/${passMail.ID}`)).json() : {};
check('the pass email has the QR inline', (detail.Inline ?? []).some((a) => a.ContentType === 'image/png'),
    (detail.Inline ?? []).map((a) => a.ContentType).join(','));
check('the pass email has the calendar invite', (detail.Attachments ?? []).some((a) => a.FileName === seat.code + '.ics'),
    (detail.Attachments ?? []).map((a) => a.FileName).join(','));

// ---- the scanning event: LIVE soon, both students in, the volunteer at MAIN ----------------------------------------
const code = 'P7S' + stamp;
const event = await makeEvent(code, 'Phase 7 Scan ' + stamp, 20);
const gate = await api('POST', `/api/events/${event.id}/gates`, organizer, { code: 'MAIN', name: 'Main Gate' });
await api('POST', `/api/events/${event.id}/volunteers`, organizer, { email: 'volunteer@mitpass.test', gateId: gate.id });
await api('POST', '/api/registrations', priya, { eventId: event.id });
await api('POST', '/api/registrations', arun, { eventId: event.id });
// The scheduler starts an event only once registration is closed; close it now instead of waiting 15 minutes.
await api('POST', `/api/events/${event.id}/transitions`, organizer, { to: 'REGISTRATION_CLOSED' });
const passOf = async (token) => waitFor(async () => {
    const mine = await api('GET', '/api/passes/me?size=50', token);
    return mine.content.find((p) => p.eventId === event.id && p.status === 'ACTIVE') ?? null;
}, 30000, 1000);
const priyaPass = await passOf(priya);
const arunPass = await passOf(arun);
check('both have ACTIVE passes', !!priyaPass && !!arunPass, `${priyaPass?.passNumber} ${arunPass?.passNumber}`);
const live = await waitFor(async () => (await api('GET', `/api/events/${event.id}`, organizer)).status === 'LIVE', 200000, 3000);
check('the scheduler made the event LIVE', !!live, `${code} #${event.id}`);

// ---- browser ------------------------------------------------------------------------------------------------------
const browser = await chromium.launch(CHANNEL ? { channel: CHANNEL } : {});
function watch(page, name) {
    page.on('console', (m) => { if (m.type() === 'error') report.consoleErrors.push(`${name}: ${m.text()}`); });
    page.on('pageerror', (e) => report.consoleErrors.push(`${name}: ${e}`));
}
async function portalLogin(ctx, email) {
    const p = await ctx.newPage();
    await p.goto(PORTAL + '/login');
    await p.fill('#email', email);
    await p.fill('#password', 'Passw0rd!');
    await Promise.all([p.waitForURL('**/events'), p.click('form.card button[type=submit]')]);
    return p;
}
async function shot(page, name) {
    await page.waitForTimeout(500);
    await page.screenshot({ path: join(OUT, name + '.png'), fullPage: true });
}
async function noSideways(page, name) {
    const wide = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    check(`${name}: no sideways scroll`, wide <= 1, `${wide}px`);
}

// 3. Add to calendar (anonymous)
const anon = await browser.newContext({ viewport: DESKTOP });
const anonPage = await anon.newPage();
watch(anonPage, 'anon');
await anonPage.goto(`${PORTAL}/events/${event.id}`);
const [download] = await Promise.all([anonPage.waitForEvent('download'), anonPage.click('a[href$="/calendar.ics"]')]);
check('Add to calendar downloads the invite', download.suggestedFilename() === code + '.ics', download.suggestedFilename());
await anon.close();

// 1. the organizer's live dashboard (desktop, light)
const orgCtx = await browser.newContext({ viewport: DESKTOP, colorScheme: 'light' });
const dash = await portalLogin(orgCtx, 'organizer.a@mitpass.test');
watch(dash, 'dashboard');
await dash.goto(`${PORTAL}/organizer/events/${event.id}/live`);
await dash.waitForSelector('#live-status.badge--live', { timeout: 10000 }).catch(() => {});
check('the dashboard connected (Live badge)', (await dash.getAttribute('#live-status', 'class')).includes('badge--live'));
await shot(dash, '01-dashboard-desktop-light');

// the volunteer's scanner (phone)
const volCtx = await browser.newContext({ viewport: PHONE, deviceScaleFactor: 2, colorScheme: 'dark' });
const scan = await portalLogin(volCtx, 'volunteer@mitpass.test');
watch(scan, 'scanner');
await scan.goto(PORTAL + '/scan');
await scan.selectOption('#gate', String(gate.id));
await scan.click('[data-tab=manual]').catch(() => {});
// online: Priya by typed number → visual check → confirm
await scan.fill('#code', priyaPass.passNumber);
await scan.click('#manual-panel button[type=submit]');
await scan.waitForSelector('#scan-result >> text=Is this the person?', { timeout: 10000 });
const t0 = Date.now();
await scan.click('#scan-result button[type=submit]');
await scan.waitForSelector('#scan-result .result--green', { timeout: 10000 });
const updated = await dash.waitForFunction(() => document.getElementById('live-checked').textContent.trim() === '1',
    null, { timeout: 5000 }).then(() => true).catch(() => false);
const ms = Date.now() - t0;
check('a scan moves the dashboard without a reload', updated, `${ms} ms after confirming`);
check('…within about a second', updated && ms < 2000, `${ms} ms`);
check('the ticker shows her first name', (await dash.textContent('#live-recent')).includes('Priya'));
await shot(dash, '02-dashboard-after-scan');
await scan.click('[data-scan-next]');

// 4. offline: WebCrypto and Luhn on real data
const bundle = await scan.evaluate(async (eventId) => {
    for (let i = 0; i < 20; i++) {
        const r = await fetch('/scan/offline/bundle?eventId=' + eventId);
        if (r.ok) return r.json();
        await new Promise((x) => setTimeout(x, 500));
    }
    return null;
}, event.id);
const liveToken = (await api('GET', `/api/passes/${arunPass.id}/qr/live`, arun, null, 'application/json')).token;
const verdicts = await scan.evaluate(async ({ token, jwks }) => {
    const ok = await window.MitpassOffline._verify(token, jwks);
    const flipped = token.slice(0, -3) + (token.slice(-3, -2) === 'A' ? 'B' : 'A') + token.slice(-2);
    const bad = await window.MitpassOffline._verify(flipped, jwks);
    return { ok: !!ok.claims, bad: !!bad.bad };
}, { token: liveToken, jwks: bundle.jwks });
check('WebCrypto verifies a real live code', verdicts.ok);
check('…and refuses it with one character changed', verdicts.bad);
const luhn = await scan.evaluate(([good]) => {
    const typo = good.slice(0, -1) + String((Number(good.slice(-1)) + 1) % 10);
    return [window.MitpassOffline._passNumberValid(good), window.MitpassOffline._passNumberValid(typo)];
}, [arunPass.passNumber]);
check('Luhn: the real number passes, a typo in the check digit fails', luhn[0] && !luhn[1], luhn.join(','));
check('the bundle has no token in it', !JSON.stringify(bundle).includes('eyJ'));
await sleep(1500); // let offline.js store the bundle for the selected gate

// offline: Arun (green on the phone), Priya again (green on the phone: this phone never admitted her)
await volCtx.setOffline(true);
await scan.waitForFunction(() => document.getElementById('online').textContent.includes('Offline'), null, { timeout: 5000 });
check('the badge says Offline', true);
check('check-out is locked offline', await scan.isDisabled('input[name=mode][value=OUT]'));
for (const number of [arunPass.passNumber, priyaPass.passNumber]) {
    await scan.fill('#code', number);
    await scan.click('#manual-panel button[type=submit]');
    await scan.waitForSelector('#scan-result .result', { timeout: 5000 });
    await scan.click('[data-scan-next]');
}
const badge = await scan.textContent('#online');
check('two scans queued offline', badge.includes('2 queued'), badge.trim());
await shot(scan, '03-scanner-offline-phone-dark');
await noSideways(scan, 'offline scanner (360 px)');

// back online: the queue syncs, Priya's comes back DUPLICATE_OFFLINE, the dashboard counts Arun
await volCtx.setOffline(false);
await scan.waitForSelector('#sync-list:not([hidden]) li', { timeout: 30000 }).catch(() => {});
const synced = await scan.textContent('#sync-items').catch(() => '');
check('the queue synced', synced.includes('Admitted'), synced.replace(/\s+/g, ' ').trim());
check("Priya's offline scan came back DUPLICATE_OFFLINE", synced.includes('Already in (offline)'));
check('…and is marked as changed', (await scan.locator('#sync-items li.is-changed').count()) >= 1);
const badgeAfter = await scan.textContent('#online');
check('the badge is back online', !badgeAfter.includes('Offline'), badgeAfter.trim());
const counted = await dash.waitForFunction(() => document.getElementById('live-checked').textContent.trim() === '2',
    null, { timeout: 5000 }).then(() => true).catch(() => false);
check('the dashboard counts the synced scan', counted);
await shot(scan, '04-scanner-synced');

// the service worker serves the scanner page with no network
const controlled = await waitFor(() => scan.evaluate(() => !!navigator.serviceWorker && !!navigator.serviceWorker.controller), 10000);
if (!controlled) { await scan.reload(); }
await scan.waitForTimeout(1000);
await volCtx.setOffline(true);
const reloaded = await scan.reload().then(() => true).catch(() => false);
check('offline, the scanner page still loads (service worker)', reloaded && (await scan.locator('#gate').count()) === 1);
await volCtx.setOffline(false);

// dashboard: dark mode and phone width
const darkCtx = await browser.newContext({ viewport: PHONE, deviceScaleFactor: 2, colorScheme: 'dark' });
const dashPhone = await portalLogin(darkCtx, 'organizer.a@mitpass.test');
watch(dashPhone, 'dashboard-phone');
await dashPhone.goto(`${PORTAL}/organizer/events/${event.id}/live`);
await shot(dashPhone, '05-dashboard-phone-dark');
await noSideways(dashPhone, 'dashboard (360 px)');

// 5. dead letters: a poison message → DLQ → amber health → listed → discarded
const poisonId = '00000000-0000-4000-8000-' + Date.now().toString().slice(-12);
const published = await fetch(`${RABBIT}/api/exchanges/%2F/amq.default/publish`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: 'Basic ' + Buffer.from('mitpass:mitpass').toString('base64') },
    body: JSON.stringify({
        properties: { message_id: poisonId, type: 'pass.issued', content_type: 'application/json' },
        routing_key: 'notification-service.inbox', payload: '{"not": "an envelope"', payload_encoding: 'string',
    }),
}).then((r) => r.json());
check('a poison message published to notification-service.inbox', published.routed === true);
const dead = await waitFor(async () => {
    const o = await api('GET', '/api/admin/dead-letters/notification-service', admin);
    return o.messages.some((m) => m.messageId === poisonId) ? o : null;
}, 30000, 1000);
check('…it reached the DLQ after its retries', !!dead);
const adminCtx = await browser.newContext({ viewport: DESKTOP, colorScheme: 'light' });
const adm = await portalLogin(adminCtx, 'admin@mitpass.test');
watch(adm, 'admin');
await adm.goto(PORTAL + '/admin/health');
check('Admin · Health shows the warning in amber', (await adm.locator('.flash--warn').count()) === 1);
await shot(adm, '06-health-warning');
await adm.goto(PORTAL + '/admin/dead-letters');
check('Admin · Dead letters lists it', (await adm.textContent('main')).includes('pass.issued'));
await shot(adm, '07-dead-letters');
const row = adm.locator('tr', { hasText: 'pass.issued' }).first();
await row.locator('button[data-open-dialog]').click();
await adm.locator('dialog[open] button.btn--danger').click();
await adm.waitForSelector('.flash', { timeout: 10000 });
check('discard asks first and then removes it', (await adm.textContent('.flash')).includes('Discarded'));
const left = await api('GET', '/api/admin/dead-letters/notification-service', admin);
check('…the DLQ is empty again', !left.messages.some((m) => m.messageId === poisonId));
await adm.goto(PORTAL + '/admin/health');
await shot(adm, '08-health-after');

await browser.close();
check('no console errors', report.consoleErrors.length === 0, report.consoleErrors.slice(0, 3).join(' | '));
writeFileSync(join(OUT, 'report.json'), JSON.stringify(report, null, 2));
const failed = report.checks.filter((c) => !c.ok).length;
console.log(failed ? `${failed} check(s) failed` : 'all checks passed');
process.exit(failed ? 1 : 0);
