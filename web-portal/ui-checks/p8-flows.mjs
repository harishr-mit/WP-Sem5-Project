// Phase 8 state-changing flows, through the portal's real UI, each checked against the API afterwards (these are steps
// 4.1, 4.4 and 7 of docs/DEMO_SCRIPT.md): admin Close registrations → Start now; the organizer's Void check-in; an admin
// revokes a pass and issues a new one; cancel an event and Reinstate it (new pass); Ban → Lift ban.
// Creates its own events, so it can run repeatedly. Needs the stack with the bulk seed (README "Quick start").
// Usage: [SCHEME=dark WIDTH=360] node p8-flows.mjs <screenshot-folder>
import { chromium } from 'playwright';
import { join } from 'node:path';
import { mkdirSync } from 'node:fs';

const PORTAL = process.env.PORTAL_URL ?? 'http://localhost:8090';
const GATEWAY = process.env.GATEWAY_URL ?? 'http://127.0.0.1:8080';
const SCHEME = process.env.SCHEME ?? 'light';
const WIDTH = Number(process.env.WIDTH ?? 1280);
const OUT = process.argv[2] ?? '.';
mkdirSync(OUT, { recursive: true });
let failed = 0;
const check = (name, ok, detail = '') => {
    if (!ok) failed++;
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  (' + detail + ')' : ''}`);
};

// ---- API helpers (every call from its own "address": the gateway limits logins per address) ----------------------------
let n = 0;
const xff = () => `10.200.${Math.floor(Date.now() / 1000) % 250}.${(n++ % 250) + 1}`;
async function api(method, path, token, body) {
    const r = await fetch(GATEWAY + path, {
        method,
        headers: { 'Content-Type': 'application/json', Accept: 'application/json', 'X-Forwarded-For': xff(), ...(token ? { Authorization: `Bearer ${token}` } : {}) },
        body: body ? JSON.stringify(body) : undefined,
    });
    const text = await r.text();
    let json = null;
    try { json = JSON.parse(text); } catch { /* not JSON */ }
    return { status: r.status, json };
}
const apiLogin = async (email) => (await api('POST', '/api/auth/login', null, { email, password: 'Passw0rd!' })).json?.accessToken;
const until = async (fn, what) => {
    for (let i = 0; i < 40; i++) {
        const v = await fn();
        if (v) return v;
        await new Promise((r) => setTimeout(r, 1000));
    }
    throw new Error('timed out: ' + what);
};
const iso = (min) => new Date(Date.now() + min * 60_000).toISOString();

const organizerToken = await apiLogin('organizer.a@mitpass.test');
const priyaToken = await apiLogin('2026509001@student.annauniv.edu');
const volunteerToken = await apiLogin('volunteer@mitpass.test');
const stamp = Date.now().toString().slice(-7);

async function openEvent(codePrefix) {
    const code = `${codePrefix}${stamp}`;
    const created = await api('POST', '/api/events', organizerToken, {
        code, title: `Flows ${code}`, categoryId: 1, venueId: 1, capacity: 10, waitlistEnabled: false,
        // 3 h ahead: further out than the scheduler's doors-open lead (30 min), so only an admin can start it now
        startsAt: iso(180), endsAt: iso(360), registrationClosesAt: iso(150),
    });
    const id = created.json.id;
    for (const to of ['PUBLISHED', 'REGISTRATION_OPEN']) await api('POST', `/api/events/${id}/transitions`, organizerToken, { to });
    const reg = await api('POST', '/api/registrations', priyaToken, { eventId: id });
    check(`${code}: priya registered`, reg.status === 201);
    return { id, code };
}
const priyaPasses = async (eventId) =>
    (await api('GET', '/api/passes/me?size=100', priyaToken)).json.content.filter((p) => p.eventId === eventId);

const browser = await chromium.launch(process.env.PW_CHANNEL === '' ? {} : { channel: process.env.PW_CHANNEL ?? 'msedge' });
const states = {};
async function login(email) {
    if (states[email]) return states[email];
    const ctx = await browser.newContext();
    const page = await ctx.newPage();
    await page.goto(PORTAL + '/login');
    await page.fill('#email', email);
    await page.fill('#password', 'Passw0rd!');
    await Promise.all([page.waitForURL((u) => !u.pathname.endsWith('/login')), page.click('form.card button[type=submit]')]);
    states[email] = await ctx.storageState();
    await ctx.close();
    return states[email];
}
async function session(email) {
    const ctx = await browser.newContext({
        colorScheme: SCHEME, viewport: { width: WIDTH, height: WIDTH < 500 ? 780 : 900 }, storageState: await login(email),
    });
    const page = await ctx.newPage();
    page.errors = [];
    page.on('pageerror', (e) => page.errors.push(String(e)));
    page.on('console', (m) => m.type() === 'error' && page.errors.push(m.text()));
    return page;
}
const shot = (p, name) => p.screenshot({ path: join(OUT, `${name}-${SCHEME}-${WIDTH}.png`), fullPage: true });
const flash = async (p) => (await p.locator('.flash, [role=status], [role=alert]').first().innerText().catch(() => '')).trim();
const row = (p, code) => p.locator('tr', { hasText: code });
/** A critical move: the button opens a dialog (JS); its submit does the move. */
async function dialogMove(p, code, label) {
    await row(p, code).locator('button', { hasText: label }).first().click();
    const d = p.locator('dialog[open]');
    await d.waitFor();
    return d;
}

const admin = await session('admin@mitpass.test');
const organizer = await session('organizer.a@mitpass.test');

// ---- 1. Close registrations, then the admin's "Start now" ----------------------------------------------------------
const e1 = await openEvent('PWA');
await priyaPasses(e1.id).then((p) => until(async () => (await priyaPasses(e1.id)).length > 0, 'the pass'));
await admin.goto(PORTAL + '/organizer/events');
await Promise.all([admin.waitForNavigation(), row(admin, e1.code).locator('button', { hasText: 'Close registrations' }).click()]);
check('admin: Close registrations → the row now offers Start now', (await row(admin, e1.code).innerText()).includes('Start now'));
const startDialog = await dialogMove(admin, e1.code, 'Start now');
check('Start now asks first and explains', (await startDialog.innerText()).includes('goes live now'));
await shot(admin, 'start-now-dialog');
await Promise.all([admin.waitForNavigation(), startDialog.locator('button[type=submit]').click()]);
const ev1 = await api('GET', `/api/events/${e1.id}`, organizerToken);
check('Start now: the event is LIVE (API)', ev1.json.status === 'LIVE', ev1.json.status);
await shot(admin, 'events-after-start');

// ---- 2. a scan, then the organizer voids it ------------------------------------------------------------------------
const gate = (await api('POST', `/api/events/${e1.id}/gates`, organizerToken, { code: 'MAIN', name: 'Main' })).json;
await api('POST', `/api/events/${e1.id}/volunteers`, organizerToken, { email: 'volunteer@mitpass.test', gateId: gate.id });
const pass1 = (await priyaPasses(e1.id))[0];
const scan = await until(async () => {
    const live = (await api('GET', `/api/passes/${pass1.id}/qr/live`, priyaToken)).json?.token;
    const r = await api('POST', '/api/scans', volunteerToken, { token: live, gateId: gate.id, deviceId: 'pw' });
    return r.json?.result === 'ACCEPTED' ? r : null;
}, 'an accepted scan');
check('scan accepted', scan.status === 200);

await organizer.goto(`${PORTAL}/organizer/events/${e1.id}/attendance`);
await shot(organizer, 'attendance-with-void');
await Promise.all([organizer.waitForURL('**/void'), organizer.locator('a', { hasText: 'Void check-in' }).first().click()]);
check('Void check-in: the confirmation page asks for a reason', /void this check-in/i.test(await organizer.locator('h1').innerText()));
await shot(organizer, 'confirm-void');
await organizer.fill('#reason', 'Scanned the wrong ticket');
await Promise.all([organizer.waitForURL(`**/events/${e1.id}/attendance`), organizer.locator('form button[type=submit]').last().click()]);
const summary = await api('GET', `/api/attendance/events/${e1.id}/summary`, organizerToken);
check('voided: attendance is 0 (API)', summary.json.checkedIn === 0, String(summary.json.checkedIn));
check('voided: the page confirms it', /voided/i.test(await organizer.innerText('main')));

// ---- 3. revoke the pass, then the admin issues a new one -----------------------------------------------------------
await admin.goto(`${PORTAL}/organizer/events/${e1.id}/registrations`);
await admin.locator('button', { hasText: 'Revoke' }).first().click();
await Promise.all([admin.waitForNavigation(), admin.locator('dialog[open] button[type=submit]').click()]);
check('revoked (API)', (await priyaPasses(e1.id))[0].status === 'REVOKED');
await admin.goto(`${PORTAL}/organizer/events/${e1.id}/registrations`);
await shot(admin, 'registrations-revoked');
await Promise.all([admin.waitForURL('**/reissue/confirm**'), admin.locator('button', { hasText: 'Issue a new pass' }).first().click()]);
check('Issue a new pass: the confirmation page explains', (await admin.locator('main').innerText()).includes('stays revoked'));
await shot(admin, 'confirm-reissue');
await Promise.all([admin.waitForURL(`**/events/${e1.id}/registrations`), admin.locator('form button[type=submit]').last().click()]);
const after = await priyaPasses(e1.id);
check('a new pass exists, the old one stays revoked (API)', after.length === 2 && after.filter((p) => p.status === 'REVOKED').length === 1 && after.some((p) => p.status === 'ACTIVE' || p.status === 'ISSUED'), after.map((p) => p.status).join(','));

// ---- 4. cancel an event (the code is typed), then Reinstate it ------------------------------------------------------
const e2 = await openEvent('PWB');
await until(async () => (await priyaPasses(e2.id)).length > 0, 'the pass of the second event');
await admin.goto(PORTAL + '/organizer/events');
const cancel = await dialogMove(admin, e2.code, 'Cancel event');
await cancel.locator('input[name=code]').fill(e2.code);
await Promise.all([admin.waitForNavigation(), cancel.locator('button[type=submit]').click()]);
check('cancelled (API)', (await api('GET', `/api/events/${e2.id}`, organizerToken)).json.status === 'CANCELLED');
await until(async () => (await priyaPasses(e2.id))[0]?.status === 'REVOKED', 'the cancellation saga to revoke the pass');
await admin.goto(PORTAL + '/organizer/events');
check('a cancelled event offers Reinstate (admin only)', (await row(admin, e2.code).innerText()).includes('Reinstate event'));
const reinstate = await dialogMove(admin, e2.code, 'Reinstate event');
check('Reinstate explains the new passes', (await reinstate.innerText()).includes('new pass'));
await shot(admin, 'reinstate-dialog');
await Promise.all([admin.waitForNavigation(), reinstate.locator('button[type=submit]').click()]);
check('reinstated to where it was (API)', (await api('GET', `/api/events/${e2.id}`, organizerToken)).json.status === 'REGISTRATION_OPEN');
const reissued = await until(async () => {
    const p = await priyaPasses(e2.id);
    return p.length === 2 && p.some((x) => x.status === 'ISSUED' || x.status === 'ACTIVE') ? p : null;
}, 'the new pass after reinstating');
check('reinstate gave priya a new pass; the old one stays revoked', reissued.filter((p) => p.status === 'REVOKED').length === 1);

// ---- 5. ban and lift ----------------------------------------------------------------------------------------------
const victim = '2027503001@student.annauniv.edu';
await admin.goto(`${PORTAL}/admin/users?q=2027503001`);
await Promise.all([admin.waitForURL('**/admin/users/**'), admin.locator('a[href*="/admin/users/"]').first().click()]);
await admin.click('button[data-open-dialog=ban-user]');
await shot(admin, 'ban-dialog-open');
await Promise.all([admin.waitForNavigation(), admin.locator('dialog#ban-user button[type=submit]').click()]);
await admin.waitForSelector('text=Lift ban');
check('Ban: the page shows Banned and offers Lift ban', /banned/i.test(await admin.innerText('main')));
await shot(admin, 'user-banned');
const refused = await api('POST', '/api/auth/login', null, { email: victim, password: 'Passw0rd!' });
check('a banned user cannot log in (403 account-disabled)', refused.status === 403 && refused.json?.type?.endsWith('account-disabled'), String(refused.status));
await Promise.all([admin.waitForNavigation(), admin.locator('button', { hasText: 'Lift ban' }).click()]);
await admin.waitForSelector('button[data-open-dialog=ban-user]');
check('Lift ban: they can log in again', (await api('POST', '/api/auth/login', null, { email: victim, password: 'Passw0rd!' })).status === 200);

check('no console errors in the admin session', admin.errors.length === 0, admin.errors.join(' | '));
check('no console errors in the organizer session', organizer.errors.length === 0, organizer.errors.join(' | '));
await browser.close();
console.log(failed ? `${failed} check(s) FAILED` : 'all checks passed');
process.exit(failed ? 1 : 0);
