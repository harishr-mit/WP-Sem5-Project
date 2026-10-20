// Phase 7 exit criterion 2, reminders: an event starting in just under an hour gets its "starts in 1 hour" email once
// per registration, also with two notification-service instances running (ShedLock + the dedup key).
// Start both instances with --mitpass.notifications.reminders.interval=10s (the second one on another port), then:
//   node reminders.mjs
const GATEWAY = process.env.GATEWAY_URL ?? 'http://localhost:8080';
const MAILPIT = process.env.MAILPIT_URL ?? 'http://localhost:8025';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let failed = 0;
function check(name, ok, detail = '') {
    if (!ok) failed++;
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  (' + detail + ')' : ''}`);
}
async function api(method, path, token, body) {
    const res = await fetch(GATEWAY + path, {
        method,
        headers: { ...(body ? { 'Content-Type': 'application/json' } : {}), ...(token ? { Authorization: 'Bearer ' + token } : {}) },
        body: body ? JSON.stringify(body) : undefined,
    });
    if (!res.ok) throw new Error(`${method} ${path} -> ${res.status} ${await res.text()}`);
    return res.json().catch(() => null);
}
const login = async (email) => (await api('POST', '/api/auth/login', null, { email, password: 'Passw0rd!' })).accessToken;
const count = async (to, subject) => {
    const r = await (await fetch(`${MAILPIT}/api/v1/search?query=${encodeURIComponent(`to:${to} subject:"${subject}"`)}`)).json();
    return r.messages_count ?? (r.messages ?? []).length;
};

const organizer = await login('organizer.a@mitpass.test');
const students = ['2026509001@student.annauniv.edu', '2026505014@student.annauniv.edu'];
const now = Date.now();
const iso = (ms) => new Date(ms).toISOString();
const title = 'Reminder Check ' + now.toString(36).toUpperCase().slice(-5);
const event = await api('POST', '/api/events', organizer, {
    code: 'RMD' + now.toString(36).toUpperCase().slice(-5), title, categoryId: 1, venueId: 1, capacity: 10,
    startsAt: iso(now + 59 * 60_000), endsAt: iso(now + 3 * 60 * 60_000), registrationClosesAt: iso(now + 50 * 60_000),
});
await api('POST', `/api/events/${event.id}/transitions`, organizer, { to: 'PUBLISHED' });
await api('POST', `/api/events/${event.id}/transitions`, organizer, { to: 'REGISTRATION_OPEN' });
for (const email of students) {
    await api('POST', '/api/registrations', await login(email), { eventId: event.id });
}
console.log(`event #${event.id} "${title}" starts in 59 min; waiting for the 1 h reminders…`);

const subject = `${title} starts in 1 hour`;
let got = [0, 0];
for (let i = 0; i < 24 && got.some((n) => n === 0); i++) {
    await sleep(5000);
    got = await Promise.all(students.map((s) => count(s, subject)));
}
check('every registration got its 1 h reminder', got.every((n) => n >= 1), got.join(','));
await sleep(30000); // several more runs of both instances
got = await Promise.all(students.map((s) => count(s, subject)));
check('…exactly once, with two instances running', got.every((n) => n === 1), got.join(','));
process.exit(failed ? 1 : 0);
