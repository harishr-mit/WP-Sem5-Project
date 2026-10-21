/*
 * The scanner's app shell for offline use (P7, docs/plans/phase-7.md §7). Registered by offline.js with scope /scan,
 * so it only serves the scanner page and what that page loads. The page is network-first (a fresh one whenever
 * possible, the last good copy without network); styles, scripts, fonts and WebJars are cache-first (their URLs are
 * content-hashed or versioned). Nothing else is cached: the pass data lives in IndexedDB, and POSTs always go out.
 */
var CACHE = 'mitpass-scan-v1';

self.addEventListener('install', function () { self.skipWaiting(); });

self.addEventListener('activate', function (event) {
    event.waitUntil(caches.keys().then(function (names) {
        return Promise.all(names.filter(function (n) { return n !== CACHE; }).map(function (n) { return caches.delete(n); }));
    }).then(function () { return self.clients.claim(); }));
});

function keep(request, response) {
    if (response.ok && response.type === 'basic') {
        var copy = response.clone();
        caches.open(CACHE).then(function (cache) { cache.put(request, copy); });
    }
    return response;
}

self.addEventListener('fetch', function (event) {
    var request = event.request;
    var url = new URL(request.url);
    if (request.method !== 'GET' || url.origin !== self.location.origin) {
        return;
    }
    if (url.pathname === '/scan') {
        event.respondWith(fetch(request)
            .then(function (response) { return response.redirected ? response : keep(request, response); })
            .catch(function () { return caches.match(request, { ignoreSearch: true }); }));
        return;
    }
    if (/^\/(css|js|fonts|webjars)\//.test(url.pathname) || url.pathname.indexOf('/favicon') === 0) {
        event.respondWith(caches.match(request).then(function (hit) {
            return hit || fetch(request).then(function (response) { return keep(request, response); });
        }));
    }
});
