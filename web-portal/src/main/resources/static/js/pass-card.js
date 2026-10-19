/*
 * The live code on the pass card (UI reference screen 4; D9 row "plain 30 s timer" replaced).
 * Every data-refresh-ms (30 s; live tokens last 45 s) the next image is loaded off-screen and swapped in only once it
 * has arrived, so the code never blanks or half-paints (RL6). The ring and the "new code in N s" count down to the
 * next swap. A failed load (pass revoked or expired meanwhile, session over) stops the timer and says so.
 */
(function () {
    'use strict';
    const frame = document.getElementById('pass-code');
    if (!frame) {
        return;
    }
    const img = document.getElementById('live-qr');
    const left = document.getElementById('qr-left');
    const ring = document.querySelector('.pass__live .ring__left');
    const gone = document.getElementById('qr-gone');
    const refreshMs = parseInt(frame.dataset.refreshMs, 10);
    const base = frame.dataset.src;
    if (!(refreshMs >= 5000)) {
        return; // never hammer pass-service: a missing or tiny interval means a broken page, not "reload constantly"
    }
    let due = Date.now() + refreshMs;
    let stopped = false;

    function stop() {
        stopped = true;
        img.hidden = true;
        const view = document.getElementById('qr-zoom-view');
        if (view) {
            view.hidden = true;
        }
        if (gone) {
            gone.hidden = false;
        }
    }

    function swap() {
        const next = new Image();
        next.onload = function () {
            img.src = next.src;
            const viewImg = document.getElementById('live-qr-zoom');
            if (viewImg && !viewImg.closest('[hidden]')) {
                viewImg.src = next.src; // the full-screen view shows the new code too
            }
        };
        next.onerror = stop;
        next.src = base + '?t=' + Date.now();
        due = Date.now() + refreshMs;
    }

    function tick() {
        if (stopped) {
            return;
        }
        const remaining = Math.max(0, due - Date.now());
        if (left) {
            left.textContent = String(Math.ceil(remaining / 1000));
        }
        if (ring) {
            // 0 = full ring, 100 = empty; pathLength="100" on the circle
            ring.style.strokeDashoffset = String(100 - (remaining / refreshMs) * 100);
        }
        if (remaining === 0) {
            swap();
        }
        setTimeout(tick, 1000 - (Date.now() % 1000));
    }

    // Tap the code for full screen on white, tap again (or Escape) to close: at card size a scanning phone has to come
    // so close that its camera can't focus (found in the two-phone demo). The image keeps refreshing while zoomed.
    // The view sits outside the card (its backdrop-filter would trap a fixed overlay inside the card).
    const zoom = frame.querySelector('[data-qr-zoom]');
    const view = document.getElementById('qr-zoom-view');
    const viewImg = document.getElementById('live-qr-zoom');
    function setZoom(on) {
        if (!zoom || !view) {
            return;
        }
        frame.classList.toggle('is-zoomed', on);
        zoom.setAttribute('aria-pressed', String(on));
        if (on) {
            viewImg.src = img.src;
            viewImg.alt = img.alt;
        }
        view.hidden = !on;
        (on ? view : zoom).focus();
    }
    if (zoom && view) {
        zoom.addEventListener('click', function () { setZoom(true); });
        view.addEventListener('click', function () { setZoom(false); });
        document.addEventListener('keydown', function (event) {
            if (event.key === 'Escape' && !view.hidden) {
                setZoom(false);
            }
        });
    }

    img.onerror = stop;
    // Back from the background (phone locked, tab switched): the shown code may be stale, so fetch a new one now.
    document.addEventListener('visibilitychange', function () {
        if (!stopped && document.visibilityState === 'visible' && Date.now() > due - refreshMs + 1000) {
            swap();
        }
    });
    tick();
})();
