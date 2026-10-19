// The admin pages in a real browser (see README.md). Usage: node admin.mjs <screenshot-folder>
// Changes nothing: the ADMIN grant is only taken to its confirmation, the key dialogs are opened and closed.
import { chromium } from 'playwright';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const PORTAL = process.env.PORTAL_URL ?? 'http://localhost:8090';
const CHANNEL = process.env.PW_CHANNEL ?? 'msedge';
const OUT = process.argv[2];
if (!OUT) {
    console.error('usage: node admin.mjs <screenshot-folder>');
    process.exit(2);
}
mkdirSync(OUT, { recursive: true });
const report = { checks: [], consoleErrors: [] };
function check(name, ok, detail = '') {
    report.checks.push({ name, ok, detail });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  (' + detail + ')' : ''}`);
}

const browser = await chromium.launch(CHANNEL ? { channel: CHANNEL } : {});
const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 } });
const p = await ctx.newPage();
p.on('console', (m) => { if (m.type() === 'error') report.consoleErrors.push(m.text()); });
p.on('pageerror', (e) => report.consoleErrors.push(String(e)));
const shot = (name) => p.screenshot({ path: join(OUT, name + '.png'), fullPage: true });

await p.goto(PORTAL + '/login');
await p.fill('#email', 'admin@mitpass.test');
await p.fill('#password', 'Passw0rd!');
await Promise.all([p.waitForURL('**/events'), p.click('form.card button[type=submit]')]);

await p.goto(PORTAL + '/admin/users');
await p.fill('#q', 'karthik');
await p.waitForFunction(() => location.search.includes('q=karthik'), null, { timeout: 5000 });
await p.waitForTimeout(500);
check('user search swaps results in place', (await p.locator('#user-results tbody tr').count()) === 1);
await shot('01-users');
await p.click('#user-results a');
await p.waitForURL('**/admin/users/*');
await p.check('input[name=roles][value=ADMIN]');
await Promise.all([p.waitForLoadState('load'), p.click('form:has(input[name=roles]) button[type=submit]')]);
check('granting ADMIN asks first', (await p.textContent('main')).includes('an admin?'));
await shot('02-grant-admin');
await p.click('text=Go back');
check('going back keeps the old roles', !(await p.isChecked('input[name=roles][value=ADMIN]')));

await p.goto(PORTAL + '/admin/health');
const rows = await p.locator('.health__item').count();
check('health lists the instances', rows >= 6, `${rows} instances`);
check('components are shown', (await p.locator('.health__item .badge--ok').count()) > 0);
await shot('03-health');

await p.goto(PORTAL + '/admin/keys');
await p.click('[data-open-dialog=rotate-pass]');
check('key rotation asks in a dialog', await p.locator('dialog[open]').isVisible());
await shot('04-keys-dialog');
await p.click('dialog[open] [data-close-dialog]');

await p.setViewportSize({ width: 360, height: 780 });
for (const path of ['/admin/users', '/admin/health', '/admin/keys']) {
    await p.goto(PORTAL + path);
    const wide = await p.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    check(`no horizontal scroll: ${path}`, wide <= 1, `${wide}px`);
}
await shot('05-health-phone');

await browser.close();
check('no console errors', report.consoleErrors.length === 0, JSON.stringify(report.consoleErrors.slice(0, 3)));
writeFileSync(join(OUT, 'report.json'), JSON.stringify(report, null, 2));
const failed = report.checks.filter((c) => !c.ok).length;
console.log(`\n${report.checks.length - failed}/${report.checks.length} checks passed; screenshots in ${OUT}`);
process.exit(failed ? 1 : 0);
