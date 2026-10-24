// Phase 8 pages (docs/plans/phase-8.md): the waitlist setting on the event form, "full, no waitlist" on the event page
// (and no register button), the admin's ban dialog and Edit link on any event. Phone and desktop width, light and dark,
// no console errors, no sideways scroll. Needs the stack with the bulk seed (README "Quick start").
// Usage: node p8.mjs <screenshot-folder>
import { chromium } from 'playwright';
import { join } from 'node:path';
import { mkdirSync } from 'node:fs';

const PORTAL = process.env.PORTAL_URL ?? 'http://localhost:8090';
const GATEWAY = process.env.GATEWAY_URL ?? 'http://127.0.0.1:8080';
const OUT = process.argv[2] ?? '.';
mkdirSync(OUT, { recursive: true });
let failed = 0;
const check = (name, ok, detail = '') => {
    if (!ok) failed++;
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  (' + detail + ')' : ''}`);
};

async function eventId(code) {
    const r = await fetch(`${GATEWAY}/api/events?q=${code}`);
    return (await r.json()).content.find((e) => e.code === code)?.id;
}

const browser = await chromium.launch(process.env.PW_CHANNEL === '' ? {} : { channel: process.env.PW_CHANNEL ?? 'msedge' });

// One login per role (the gateway rate-limits logins per address, D17), then every context reuses its session.
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

async function session(email, scheme, width) {
    const ctx = await browser.newContext({
        colorScheme: scheme,
        viewport: { width, height: width < 500 ? 780 : 900 },
        storageState: await login(email),
    });
    const page = await ctx.newPage();
    page.errors = [];
    page.on('pageerror', (e) => page.errors.push(String(e)));
    page.on('console', (m) => m.type() === 'error' && page.errors.push(m.text()));
    return page;
}

const noSideways = (p) => p.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1);

const quiz = await eventId('FEST26QUIZ'); // 120 seats, no waitlist, more students than seats
const hack = await eventId('FEST26HACK');
check('seeded events found', !!quiz && !!hack, `quiz=${quiz} hack=${hack}`);

for (const [scheme, width] of [['light', 1280], ['dark', 1280], ['light', 360], ['dark', 360]]) {
    const tag = `${scheme}-${width}`;

    // organizer: the form has the waitlist checkbox, off for a seeded event without a waitlist
    let p = await session('organizer.a@mitpass.test', scheme, width);
    await p.goto(`${PORTAL}/organizer/events/${quiz}/edit`);
    const box = p.locator('input[name=waitlistEnabled][type=checkbox]');
    check(`${tag}: event form has the waitlist setting, off`, (await box.count()) === 1 && !(await box.isChecked()));
    check(`${tag}: the setting is labelled`, (await p.locator('label:has(input[name=waitlistEnabled])').innerText()).includes('waitlist'));
    check(`${tag}: event form has no sideways scroll`, await noSideways(p));
    await p.screenshot({ path: join(OUT, `form-${tag}.png`), fullPage: true });
    check(`${tag}: organizer pages without console errors`, p.errors.length === 0, p.errors.join(' | '));
    await p.context().close();

    // student: a full event without a waitlist says so and offers no register button
    p = await session('2027500001@student.annauniv.edu', scheme, width);
    await p.goto(`${PORTAL}/events/${quiz}`);
    const body = await p.innerText('main');
    check(`${tag}: full event without waitlist says so`, body.includes('full and has no waitlist'));
    check(`${tag}: no register / join-waitlist button`, (await p.locator('form[action*="/register"] button').count()) === 0);
    check(`${tag}: event page has no sideways scroll`, await noSideways(p));
    await p.screenshot({ path: join(OUT, `full-no-waitlist-${tag}.png`), fullPage: true });
    check(`${tag}: student page without console errors`, p.errors.length === 0, p.errors.join(' | '));
    await p.context().close();

    // admin: a user page with the ban dialog (opened and closed, nothing banned), and Edit on any event
    p = await session('admin@mitpass.test', scheme, width);
    await p.goto(`${PORTAL}/admin/users?q=2027500001`);
    const link = p.locator('a[href*="/admin/users/"]').first();
    await Promise.all([p.waitForURL('**/admin/users/**'), link.click()]);
    await p.click('button[data-open-dialog=ban-user]');
    const dialog = p.locator('dialog#ban-user');
    check(`${tag}: ban dialog opens with a confirmation`, await dialog.isVisible());
    await p.screenshot({ path: join(OUT, `ban-dialog-${tag}.png`) });
    await dialog.locator('[data-close-dialog]').click();
    check(`${tag}: ban dialog closes`, !(await dialog.isVisible()));
    check(`${tag}: user page shows Active, not Banned`, /active/i.test(await p.innerText('main')) && !/lift ban/i.test(await p.innerText('main')));
    check(`${tag}: user page has no sideways scroll`, await noSideways(p));
    await p.goto(`${PORTAL}/organizer/events`);
    check(`${tag}: admin sees Edit links`, (await p.locator('a[href$="/edit"]').count()) > 0);
    check(`${tag}: admin pages without console errors`, p.errors.length === 0, p.errors.join(' | '));
    await p.context().close();
}

await browser.close();
console.log(failed ? `${failed} check(s) FAILED` : 'all checks passed');
process.exit(failed ? 1 : 0);
