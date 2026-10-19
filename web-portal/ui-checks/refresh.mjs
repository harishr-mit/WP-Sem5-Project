// Exit criterion 2 (docs/plans/phase-6.md §1, §7): with short access tokens (auth-service started with
// --mitpass.auth.access-token-ttl=1m) a user keeps working without being sent to log in, and parallel requests at
// expiry never reuse a refresh token. Usage: node refresh.mjs [minutes]  (default 3)
import { chromium } from 'playwright';

const PORTAL = process.env.PORTAL_URL ?? 'http://localhost:8090';
const CHANNEL = process.env.PW_CHANNEL ?? 'msedge';
const minutes = Number(process.argv[2] ?? 3);

const browser = await chromium.launch(CHANNEL ? { channel: CHANNEL } : {});
const ctx = await browser.newContext();
const p = await ctx.newPage();
await p.goto(PORTAL + '/login');
await p.fill('#email', '2026509001@student.annauniv.edu');
await p.fill('#password', 'Passw0rd!');
await Promise.all([p.waitForURL('**/events'), p.click('form.card button[type=submit]')]);

let rounds = 0;
let loggedOut = 0;
const end = Date.now() + minutes * 60_000;
while (Date.now() < end) {
    // Several requests of one session at the same moment (page + parallel fetches), as a page with polls would make
    const results = await p.evaluate(async () => {
        const urls = ['/my/registrations', '/my/passes', '/my/stamps', '/my/profile', '/events'];
        const res = await Promise.all(urls.map((u) => fetch(u, { redirect: 'manual' }).then((r) => r.type === 'opaqueredirect' ? 302 : r.status)));
        return res;
    });
    rounds++;
    if (results.some((s) => s !== 200)) {
        loggedOut++;
        console.log(`round ${rounds}: ${results.join(',')}`);
    }
    await p.waitForTimeout(10_000);
}
await p.goto(PORTAL + '/my/registrations');
const stillIn = !p.url().includes('/login');
await browser.close();
console.log(`${rounds} rounds of 5 parallel requests over ${minutes} min; non-200 rounds: ${loggedOut}; still logged in: ${stillIn}`);
process.exit(loggedOut === 0 && stillIn ? 0 : 1);
