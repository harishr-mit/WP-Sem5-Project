// The organizer's pages in a real browser (see README.md). Usage: node organizer.mjs <screenshot-folder>
import { chromium } from 'playwright';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const PORTAL = process.env.PORTAL_URL ?? 'http://localhost:8090';
const CHANNEL = process.env.PW_CHANNEL ?? 'msedge';
const OUT = process.argv[2];
if (!OUT) {
    console.error('usage: node organizer.mjs <screenshot-folder>');
    process.exit(2);
}
mkdirSync(OUT, { recursive: true });

const report = { checks: [], consoleErrors: [], badResponses: [] };
function check(name, ok, detail = '') {
    report.checks.push({ name, ok, detail });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  (' + detail + ')' : ''}`);
}
function watch(page, label) {
    page.on('console', (m) => {
        if (m.type() === 'error') report.consoleErrors.push({ label, url: page.url(), text: m.text() });
    });
    page.on('pageerror', (e) => report.consoleErrors.push({ label, url: page.url(), text: String(e) }));
    page.on('response', (r) => {
        if (r.status() >= 500) report.badResponses.push({ label, url: r.url(), status: r.status() });
    });
}
async function shot(page, name) {
    await page.screenshot({ path: join(OUT, name + '.png'), fullPage: true });
}
async function noHorizontalScroll(page, name) {
    const wide = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    check(`no horizontal scroll: ${name}`, wide <= 1, `${wide}px`);
}

const browser = await chromium.launch(CHANNEL ? { channel: CHANNEL } : {});
const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 } });
const p = await ctx.newPage();
watch(p, 'desktop');

await p.goto(PORTAL + '/login');
await p.fill('#email', 'organizer.a@mitpass.test');
await p.fill('#password', 'Passw0rd!');
await Promise.all([p.waitForURL('**/events'), p.click('form.card button[type=submit]')]);

await p.goto(PORTAL + '/organizer/events');
await shot(p, '01-my-events');
check('my events lists actions', (await p.locator('td button').count()) > 0);

// New event: add a session with HTMX (no full reload), then save with a mistake, then fix it
await p.click('text=New event');
await p.waitForURL('**/organizer/events/new');
const code = 'ORG' + Date.now().toString(36).toUpperCase().slice(-6);
await p.fill('#code', code.toLowerCase());
await p.fill('#title', 'Organizer UI Check');
await p.selectOption('#categoryId', { index: 1 });
await p.selectOption('#venueId', { index: 1 });
await p.fill('#capacity', '25');
const mark = await p.evaluate(() => (window.__mark = Math.random()));
await p.click('button[name=_addSession]');
await p.waitForSelector('input[name="sessions[0].title"]');
check('"Add session" swapped the form in place', (await p.evaluate(() => window.__mark)) === mark);
check('typed values survive adding a session', (await p.inputValue('#title')) === 'Organizer UI Check');
await shot(p, '02-form-with-session');

// Leave the session title empty and save: errors next to the field, input kept
await Promise.all([p.waitForLoadState('load'), p.click('form#event-form > .actions button[type=submit]')]);
const err = await p.locator('.field--error .field-error').first().textContent().catch(() => '');
check('validation error shown next to the field', !!err, err);
await shot(p, '03-form-errors');

await p.fill('input[name="sessions[0].title"]', 'Opening');
await Promise.all([p.waitForURL('**/organizer/events'), p.click('form#event-form > .actions button[type=submit]')]);
const flash = await p.locator('.flash--ok').textContent();
check('created as a draft (PRG + flash)', flash.includes('draft'), flash.trim());
await shot(p, '04-created');

// The draft's row: Publish is a plain action, Cancel opens a dialog that needs the code
const row = p.locator('tr', { hasText: code });
check('code was upper-cased', (await row.count()) === 1);
const cancel = row.locator('button', { hasText: 'Cancel event' });
if (await cancel.count()) {
    await cancel.click();
    check('cancel opens a confirmation dialog', await p.locator('dialog[open]').isVisible());
    await shot(p, '05-cancel-dialog');
    await p.click('dialog[open] [data-close-dialog]');
}
await row.locator('a', { hasText: 'Edit' }).click();
await p.waitForURL('**/edit');
await shot(p, '06-edit');
await noHorizontalScroll(p, 'edit');

// An event with a registration (made by student.mjs): registrations, volunteers, reports
await p.goto(PORTAL + '/organizer/events');
const open = p.locator('tr', { hasText: 'UI Check Night' }).filter({ hasText: 'Registration open' }).first();
if (await open.count()) {
    await open.locator('a', { hasText: 'Registrations' }).click();
    await p.waitForURL('**/registrations');
    await shot(p, '09-registrations');
    const revoke = p.locator('[data-open-dialog^="revoke-"]').first();
    if (await revoke.count()) {
        await revoke.click();
        check('revoke asks in a dialog first', await p.locator('dialog[open]').isVisible());
        await shot(p, '10-revoke-dialog');
        await p.click('dialog[open] [data-close-dialog]');
    }
    await p.fill('#q', 'zzz-nobody');
    await p.waitForSelector('text=Nobody matches that search', { timeout: 5000 });
    check('registrations filter swaps rows in place', true);

    await p.click('nav.subnav >> text=Volunteers');
    await p.waitForURL('**/staff');
    await p.fill('#gate-code', 'x');
    await Promise.all([p.waitForLoadState('load'), p.click('form:has(#gate-code) button[type=submit]')]);
    check('bad gate code shown on its field', (await p.locator('.field--error .field-error').count()) > 0);
    await p.fill('#gate-code', 'gate' + Date.now().toString(36).slice(-4));
    await p.fill('#gate-name', 'Side Gate');
    await Promise.all([p.waitForURL('**/staff'), p.click('form:has(#gate-code) button[type=submit]')]);
    check('gate added', (await p.locator('.flash--ok').count()) === 1);
    await p.fill('#assign-email', '2026509001@student.annauniv.edu');
    await Promise.all([p.waitForLoadState('load'), p.click('form:has(#assign-email) button[type=submit]')]);
    const emailErr = await p.locator('.field--error .field-error').first().textContent().catch(() => '');
    check('a non-volunteer is refused on the email field', !!emailErr, emailErr);
    await shot(p, '11-staff');

    await p.click('nav.subnav >> text=Reports');
    await p.waitForURL('**/attendance');
    const mark2 = await p.evaluate(() => (window.__mark2 = Math.random()));
    await p.click('#attendance button[hx-get]');
    await p.waitForTimeout(800);
    check('reports refresh in place', (await p.evaluate(() => window.__mark2)) === mark2);
    await shot(p, '12-reports');
    await noHorizontalScroll(p, 'reports');
}

// Phone width
await p.setViewportSize({ width: 360, height: 780 });
await p.goto(PORTAL + '/organizer/events');
await shot(p, '07-my-events-phone');
await noHorizontalScroll(p, 'my events phone');
await p.goto(PORTAL + '/organizer/events/new');
await shot(p, '08-form-phone');
await noHorizontalScroll(p, 'form phone');

await browser.close();
check('no console errors', report.consoleErrors.length === 0, JSON.stringify(report.consoleErrors.slice(0, 3)));
check('no server errors', report.badResponses.length === 0, JSON.stringify(report.badResponses.slice(0, 3)));
writeFileSync(join(OUT, 'report.json'), JSON.stringify(report, null, 2));
const failed = report.checks.filter((c) => !c.ok).length;
console.log(`\n${report.checks.length - failed}/${report.checks.length} checks passed; screenshots in ${OUT}`);
process.exit(failed ? 1 : 0);
