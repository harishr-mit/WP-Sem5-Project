/*
 * The scanner's offline mode (P7, docs/plans/phase-7.md §7; owner Q2, Q3, Q12). scanner.js calls it when a scan gets
 * no answer. It then checks passes on this phone and queues every attempt; when the network is back the queue goes to
 * the server, which checks each scan again and has the last word.
 *
 *  - The bundle (/scan/offline/bundle: pass-service's public keys + checkin-service's manifest of the event) is kept in
 *    IndexedDB and fetched again every data-bundle-refresh-ms while online. It holds no token.
 *  - A QR token: ES256 signature with WebCrypto (the JWS r||s signature is the form WebCrypto expects), issuer,
 *    expiry (+ the server's clock skew), event; a typed number: format and Luhn check digit (as PassNumber.java). Then
 *    the session's doors, the pass in the manifest (unknown → amber, revoked → red) and passes already admitted on
 *    this phone. Check-in only: check-out needs the network (Q3).
 *  - The queue (IndexedDB) syncs through /scan/batch with a fresh CSRF token from /scan/csrf; the answers replace the
 *    phone's in the "Synced" list, marked where they differ (DUPLICATE_OFFLINE, a pass revoked meanwhile, ...).
 *
 * Exposes window.MitpassOffline for scanner.js.
 */
(function () {
    'use strict';
    var root = document.querySelector('main.scanner');
    if (!root || !document.getElementById('gate')) {
        return;
    }
    var gate = document.getElementById('gate');
    var badge = document.getElementById('online');
    badge.dataset.on = badge.textContent;
    var bundleRefreshMs = Number(root.getAttribute('data-bundle-refresh-ms')) || 60000;
    var syncEveryMs = Number(root.getAttribute('data-sync-every-ms')) || 15000;
    var words = {};
    document.querySelectorAll('#scan-words [data-code]').forEach(function (li) {
        words[li.getAttribute('data-code')] = li.textContent;
    });
    var COLORS = { ACCEPTED: 'green', ALREADY_CHECKED_IN: 'amber', NOT_YET_OPEN: 'amber', UNKNOWN_PASS: 'amber' };

    // ---- IndexedDB, as small promises ---------------------------------------------------------------------------
    var dbPromise = null;
    function db() {
        if (!dbPromise) {
            dbPromise = new Promise(function (resolve, reject) {
                var open = indexedDB.open('mitpass-scanner', 1);
                open.onupgradeneeded = function () {
                    open.result.createObjectStore('bundles', { keyPath: 'eventId' });
                    open.result.createObjectStore('queue', { keyPath: 'localId' });
                    open.result.createObjectStore('admitted', { keyPath: 'key' });
                };
                open.onsuccess = function () { resolve(open.result); };
                open.onerror = function () { reject(open.error); };
            });
        }
        return dbPromise;
    }
    function store(name, mode, work) {
        return db().then(function (d) {
            return new Promise(function (resolve, reject) {
                var tx = d.transaction(name, mode);
                var request = work(tx.objectStore(name));
                tx.oncomplete = function () { resolve(request ? request.result : undefined); };
                tx.onerror = function () { reject(tx.error); };
            });
        });
    }
    function get(name, key) { return store(name, 'readonly', function (s) { return s.get(key); }); }
    function put(name, value) { return store(name, 'readwrite', function (s) { return s.put(value); }); }
    function all(name) { return store(name, 'readonly', function (s) { return s.getAll(); }); }
    function remove(name, key) { return store(name, 'readwrite', function (s) { return s.delete(key); }); }

    // ---- state and badge ----------------------------------------------------------------------------------------
    var offline = false;
    var loginNeeded = false;
    var queued = 0;
    var onChange = function () {};

    function label(attr, n) { return (root.getAttribute(attr) || '').replace('{0}', String(n)); }
    function showBadge() {
        if (!offline && !loginNeeded && queued === 0) {
            badge.textContent = badge.dataset.on || badge.textContent;
            badge.classList.remove('online--off');
            return;
        }
        badge.classList.add('online--off');
        badge.textContent = loginNeeded ? label('data-login-label', queued) : label('data-queued-label', queued);
    }
    function countQueue() {
        return all('queue').then(function (items) { queued = items.length; showBadge(); }).catch(function () {});
    }
    function setOffline(on) {
        if (offline === on) { return; }
        offline = on;
        showBadge();
        onChange(on);
    }

    function currentEvent() {
        var option = gate.options[gate.selectedIndex];
        return option ? option.getAttribute('data-event') : null;
    }

    // ---- the bundle ---------------------------------------------------------------------------------------------
    function refreshBundle() {
        var eventId = currentEvent();
        if (!eventId || offline) { return Promise.resolve(); }
        return fetch('/scan/offline/bundle?eventId=' + encodeURIComponent(eventId), {
            credentials: 'same-origin', headers: { Accept: 'application/json' }
        }).then(function (r) {
            var json = r.ok && (r.headers.get('Content-Type') || '').indexOf('json') >= 0;
            return json ? r.json() : null;
        }).then(function (b) {
            if (b && b.manifest) {
                b.eventId = String(b.eventId);
                b.fetchedAt = Date.now();
                return put('bundles', b);
            }
        }).catch(function () { /* the network is gone: keep the last bundle */ });
    }

    // ---- checks -------------------------------------------------------------------------------------------------
    function bytes(b64url) {
        var s = b64url.replace(/-/g, '+').replace(/_/g, '/');
        while (s.length % 4) { s += '='; }
        var bin = atob(s);
        var out = new Uint8Array(bin.length);
        for (var i = 0; i < bin.length; i++) { out[i] = bin.charCodeAt(i); }
        return out;
    }
    function json(b64url) { return JSON.parse(new TextDecoder().decode(bytes(b64url))); }

    /** {claims} when genuine; {bad: true} when not; {unknownKey: true} for a key this phone hasn't got. */
    function verify(token, jwks) {
        var parts = token.split('.');
        if (parts.length !== 3) { return Promise.resolve({ bad: true }); }
        var header;
        try { header = json(parts[0]); } catch (e) { return Promise.resolve({ bad: true }); }
        if (header.alg !== 'ES256' || header.typ !== 'mitpass-pass+jws') { return Promise.resolve({ bad: true }); }
        var jwk = (jwks.keys || []).filter(function (k) { return k.kid === header.kid; })[0];
        if (!jwk) { return Promise.resolve({ unknownKey: true }); }
        return crypto.subtle.importKey('jwk', { kty: jwk.kty, crv: jwk.crv, x: jwk.x, y: jwk.y, ext: true },
            { name: 'ECDSA', namedCurve: 'P-256' }, false, ['verify'])
            .then(function (key) {
                return crypto.subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, key, bytes(parts[2]),
                    new TextEncoder().encode(parts[0] + '.' + parts[1]));
            })
            .then(function (ok) { return ok ? { claims: json(parts[1]) } : { bad: true }; })
            .catch(function () { return { bad: true }; });
    }

    /** As PassNumber.isValid: CODE-NNNNNN-C, Luhn over the digits with letters expanded A=10 … Z=35. */
    function passNumberValid(number) {
        var m = /^([A-Z0-9]{3,16})-(\d{6,9})-(\d)$/.exec(number);
        if (!m) { return false; }
        var digits = '';
        (m[1] + m[2]).split('').forEach(function (c) {
            digits += /[0-9]/.test(c) ? c : String(c.charCodeAt(0) - 55);
        });
        var sum = 0;
        var double = true;
        for (var i = digits.length - 1; i >= 0; i--) {
            var d = Number(digits[i]);
            if (double) { d *= 2; if (d > 9) { d -= 9; } }
            sum += d;
            double = !double;
        }
        return (10 - sum % 10) % 10 === Number(m[3]);
    }

    function answer(code, pass, message) {
        return {
            result: code,
            color: COLORS[code] || 'red',
            word: words[code] || code,
            message: message || root.getAttribute('data-checked-offline'),
            firstName: pass ? pass.firstName : null,
            passNumber: pass ? pass.passNumber : null
        };
    }

    function checkPass(bundle, pass, nowSeconds) {
        var m = bundle.manifest;
        var open = m.sessions.some(function (s) {
            var from = Date.parse(s.startsAt) / 1000 - m.doorsOpenBeforeSeconds;
            return nowSeconds >= from && nowSeconds <= Date.parse(s.endsAt) / 1000;
        });
        if (!open) {
            var first = Math.min.apply(null, m.sessions.map(function (s) { return Date.parse(s.startsAt) / 1000; }));
            return Promise.resolve(answer(nowSeconds < first - m.doorsOpenBeforeSeconds ? 'NOT_YET_OPEN' : 'EVENT_CLOSED'));
        }
        if (!pass) { return Promise.resolve(answer('UNKNOWN_PASS', null, root.getAttribute('data-no-offline-data'))); }
        if (pass.status === 'REVOKED') { return Promise.resolve(answer('REVOKED', pass)); }
        var key = m.eventId + ':' + pass.passId;
        return get('admitted', key).then(function (seen) {
            if (seen) { return answer('ALREADY_CHECKED_IN', pass); }
            return put('admitted', { key: key, at: Date.now() }).then(function () { return answer('ACCEPTED', pass); });
        });
    }

    function check(bundle, text) {
        var now = Date.now() / 1000;
        var m = bundle.manifest;
        var number = text.trim().toUpperCase();
        if (/^[A-Z0-9]{3,16}-\d{6,9}-\d$/.test(number)) {
            if (!passNumberValid(number)) { return Promise.resolve(answer('INVALID_PASS_NUMBER')); }
            var byNumber = m.passes.filter(function (p) { return p.passNumber === number; })[0];
            return checkPass(bundle, byNumber, now);
        }
        return verify(text.trim(), bundle.jwks).then(function (v) {
            if (v.unknownKey) { return answer('UNKNOWN_PASS', null, root.getAttribute('data-no-offline-data')); }
            if (v.bad || v.claims.iss !== m.issuer) { return answer('INVALID_SIGNATURE'); }
            if (now > v.claims.exp + m.clockSkewSeconds) { return answer('EXPIRED_TOKEN'); }
            if (String(v.claims.eid) !== String(m.eventId)) { return answer('WRONG_EVENT'); }
            var byId = m.passes.filter(function (p) { return p.passId === v.claims.pid; })[0];
            return checkPass(bundle, byId, now);
        });
    }

    /** Checks a QR text or typed number on this phone and queues it for the server. Resolves to the answer. */
    function scan(text) {
        var eventId = currentEvent();
        return get('bundles', String(eventId)).then(function (bundle) {
            var checked = bundle ? check(bundle, text) : Promise.resolve(answer('UNKNOWN_PASS', null,
                root.getAttribute('data-no-offline-data')));
            return checked.then(function (result) {
                var number = text.trim().toUpperCase();
                var isNumber = /^[A-Z0-9]{3,16}-\d{6,9}-\d$/.test(number);
                var item = {
                    localId: crypto.randomUUID(),
                    eventId: eventId,
                    gateId: Number(gate.value),
                    scannedAt: new Date().toISOString(),
                    localResult: result.result,
                    label: result.firstName || result.passNumber || ''
                };
                if (isNumber) { item.passNumber = number; } else { item.token = text.trim(); }
                return put('queue', item).then(countQueue).then(function () { return result; });
            });
        }).catch(function () {
            return answer('UNKNOWN_PASS', null, root.getAttribute('data-no-offline-data'));
        });
    }

    // ---- sync ---------------------------------------------------------------------------------------------------
    var syncing = false;
    function csrf() {
        return fetch('/scan/csrf', { credentials: 'same-origin', headers: { Accept: 'application/json' } })
            .then(function (r) {
                var isJson = r.ok && (r.headers.get('Content-Type') || '').indexOf('json') >= 0;
                if (!isJson) { throw new Error('login'); }
                return r.json();
            });
    }

    function listSynced(items, results) {
        var list = document.getElementById('sync-items');
        document.getElementById('sync-list').hidden = false;
        results.forEach(function (r, i) {
            var item = items[i];
            var li = document.createElement('li');
            if (r.result !== item.localResult) { li.className = 'is-changed'; }
            var at = document.createElement('span');
            at.className = 'mono';
            at.textContent = new Date(item.scannedAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: false });
            var who = document.createElement('span');
            who.textContent = r.holderName || r.passNumber || item.label;
            var word = document.createElement('span');
            word.className = 'badge ' + (r.color === 'GREEN' ? 'badge--ok' : r.color === 'AMBER' ? 'badge--warn' : 'badge--err');
            word.textContent = words[r.result] || r.result;
            li.append(at, who, word);
            list.prepend(li);
        });
    }

    /** Sends the queue (oldest first, 100 at a time). Resolves true when the server answered (we're online). */
    function sync() {
        if (syncing) { return Promise.resolve(!offline); }
        syncing = true;
        return csrf().then(function (token) {
            loginNeeded = false;
            return all('queue').then(function (items) {
                items.sort(function (a, b) { return a.scannedAt < b.scannedAt ? -1 : 1; });
                var batch = items.slice(0, 100);
                if (!batch.length) { return true; }
                var headers = { 'Content-Type': 'application/json', Accept: 'application/json' };
                headers[token.header] = token.token;
                return fetch('/scan/batch', {
                    method: 'POST', credentials: 'same-origin', headers: headers,
                    body: JSON.stringify({
                        deviceId: (window.MitpassOffline && window.MitpassOffline.deviceId) || 'web-unknown',
                        scans: batch.map(function (i) {
                            return { localId: i.localId, token: i.token, passNumber: i.passNumber, gateId: i.gateId,
                                scannedAt: i.scannedAt };
                        })
                    })
                }).then(function (r) {
                    if (!r.ok) { throw new Error('sync ' + r.status); }
                    return r.json();
                }).then(function (body) {
                    listSynced(batch, body.results);
                    return Promise.all(batch.map(function (i) { return remove('queue', i.localId); }));
                }).then(function () { return items.length > batch.length ? sync() : true; });
            });
        }).then(function () {
            setOffline(false);
            return true;
        }).catch(function (e) {
            loginNeeded = e && e.message === 'login';
            showBadge();
            return false;
        }).then(function (ok) {
            syncing = false;
            return countQueue().then(function () { return ok; });
        });
    }

    // ---- wiring -------------------------------------------------------------------------------------------------
    // The worker only caches what passes through it, and this page and its files loaded before it existed: once it
    // controls the page, fetch them again through it, so the next offline load finds them (found in the P7 check).
    function warmCache() {
        var files = performance.getEntriesByType('resource')
            .map(function (e) { return new URL(e.name); })
            .filter(function (u) { return u.origin === location.origin && /^\/(css|js|fonts|webjars)\//.test(u.pathname); })
            .map(function (u) { return u.pathname + u.search; });
        [location.pathname].concat(files).forEach(function (url) {
            fetch(url, { credentials: 'same-origin' }).catch(function () { /* offline already: nothing to add */ });
        });
    }
    if ('serviceWorker' in navigator && window.isSecureContext) {
        navigator.serviceWorker.register('/scan-sw.js', { scope: '/scan' }).catch(function () {
            /* no worker (e.g. a certificate the browser won't trust for workers): offline works while the page is open */
        });
        if (navigator.serviceWorker.controller) {
            warmCache();
        } else {
            navigator.serviceWorker.addEventListener('controllerchange', warmCache, { once: true });
        }
    }
    window.addEventListener('online', function () { sync(); });
    window.addEventListener('offline', function () { setOffline(true); });
    gate.addEventListener('change', refreshBundle);
    setInterval(function () { if (!offline) { refreshBundle(); } }, bundleRefreshMs);
    setInterval(function () { if (offline || queued > 0) { sync(); } }, syncEveryMs);

    window.MitpassOffline = {
        deviceId: null,
        isOffline: function () { return offline; },
        goOffline: function () { setOffline(true); },
        onChange: function (fn) { onChange = fn; },
        scan: scan,
        sync: sync,
        // for the browser checks (Playwright): the WebCrypto and Luhn parts on their own
        _verify: verify,
        _passNumberValid: passNumberValid
    };

    if (!navigator.onLine) { setOffline(true); }
    countQueue().then(function () { if (queued > 0) { sync(); } });
    refreshBundle();
})();
