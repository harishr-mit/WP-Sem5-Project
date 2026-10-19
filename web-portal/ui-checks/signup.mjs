// Exit criterion 1, first half (docs/plans/phase-6.md §1): sign up → the link in Mailpit opens a portal page → confirm
// → log in. Needs compose (Mailpit on :8025) and the services running. Usage: node signup.mjs
import { chromium } from 'playwright';

const PORTAL = process.env.PORTAL_URL ?? 'http://localhost:8090';
const MAILPIT = process.env.MAILPIT_URL ?? 'http://localhost:8025';
const CHANNEL = process.env.PW_CHANNEL ?? 'msedge';

let failed = 0;
function check(name, ok, detail = '') {
    if (!ok) failed++;
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  (' + detail + ')' : ''}`);
}

// A fresh register number each run, so the address is new (student emails are <register number>@student.annauniv.edu)
const regNo = '2026' + String(Date.now()).slice(-6);
const email = `${regNo}@student.annauniv.edu`;
const password = 'Techfest2026';

const browser = await chromium.launch(CHANNEL ? { channel: CHANNEL } : {});
const p = await (await browser.newContext({ ignoreHTTPSErrors: true })).newPage();
const consoleErrors = [];
p.on('console', (m) => m.type() === 'error' && consoleErrors.push(m.text()));

await p.goto(PORTAL + '/signup');
await p.fill('#fullName', 'Signup Check');
await p.fill('#email', email);
await p.selectOption('#department', { index: 1 });
await p.selectOption('#year', '1');
await p.fill('#registerNumber', regNo);
await p.fill('#password', password);
await Promise.all([p.waitForURL('**/check-email**'), p.click('form.card button[type=submit]')]);
check('sign-up lands on "check your email"', p.url().includes('/check-email'));

// The verification email, through Mailpit's API (the newest message to this address)
let link = null;
for (let i = 0; i < 20 && !link; i++) {
    const search = await (await fetch(`${MAILPIT}/api/v1/search?query=${encodeURIComponent('to:' + email)}`)).json();
    if (search.messages?.length) {
        const message = await (await fetch(`${MAILPIT}/api/v1/message/${search.messages[0].ID}`)).json();
        link = (message.Text ?? '').match(/https?:\/\/\S+verify-email\?token=[\w-]+/)?.[0] ?? null;
    }
    if (!link) await p.waitForTimeout(500);
}
check('the email arrived with a link', !!link, link ?? 'none');
check('the link opens the portal, not the API', !!link && link.startsWith(PORTAL + '/verify-email'), link ?? '');

if (link) {
    await p.goto(link);
    check('opening the link spends nothing: it asks for a press', (await p.locator('form[action$="/verify-email"] button').count()) === 1);
    await Promise.all([p.waitForURL('**/login?verified**'), p.click('form[action$="/verify-email"] button')]);
    check('confirming lands on the login page with a notice', p.url().includes('verified'));

    await p.fill('#email', email);
    await p.fill('#password', password);
    await Promise.all([p.waitForURL('**/events**'), p.click('form.card button[type=submit]')]);
    check('the new student can log in', p.url().includes('/events'));
}
check('no console errors', consoleErrors.length === 0, consoleErrors.slice(0, 3).join(' | '));
await browser.close();
process.exit(failed ? 1 : 0);
