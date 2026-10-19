// The phone-demo setup from a desktop browser (see README.md): the portal's "lan" profile over HTTPS on the laptop's
// LAN address. Checks that the page is a secure context (the camera needs one) once the self-signed certificate is
// accepted, and that the session cookie is Secure. Usage: node lan.mjs https://<laptop-ip>:8443
import { chromium } from 'playwright';

const BASE = process.argv[2];
const CHANNEL = process.env.PW_CHANNEL ?? 'msedge';
if (!BASE) {
    console.error('usage: node lan.mjs https://<laptop-ip>:8443');
    process.exit(2);
}
let failed = 0;
function check(name, ok, detail = '') {
    if (!ok) failed++;
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  (' + detail + ')' : ''}`);
}

const browser = await chromium.launch({
    ...(CHANNEL ? { channel: CHANNEL } : {}),
    args: ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream'],
});
// ignoreHTTPSErrors = a phone user pressing "continue" on the certificate warning
const ctx = await browser.newContext({ ignoreHTTPSErrors: true, permissions: ['camera'], viewport: { width: 390, height: 844 } });
const p = await ctx.newPage();
await p.goto(BASE + '/login');
check('the portal answers over HTTPS', p.url().startsWith('https://'), p.url());
await p.fill('#email', 'volunteer@mitpass.test');
await p.fill('#password', 'Passw0rd!');
await Promise.all([p.waitForURL('**/events'), p.click('form.card button[type=submit]')]);
const cookie = (await ctx.cookies()).find((c) => c.name === 'JSESSIONID');
check('the session cookie is Secure and HttpOnly', !!cookie && cookie.secure && cookie.httpOnly);
await p.goto(BASE + '/scan');
check('the scanner page is a secure context (camera allowed)', await p.evaluate(() => window.isSecureContext));
await p.waitForTimeout(3000);
const camera = await p.evaluate(() => !document.getElementById('camera-panel')?.hidden
    && !!document.querySelector('#reader video'));
check('the camera started (a fake device here)', camera);
await browser.close();
console.log(failed ? `\n${failed} check(s) failed` : '\nall checks passed');
process.exit(failed ? 1 : 0);
