/*
 * The volunteer's scanner (UI reference screens 5–6; docs/plans/phase-6.md §8). Without this file the page still works
 * (the manual form posts to /scan). With it:
 *  - the camera tab reads QR codes with html5-qrcode and posts each one to /scan/submit (HTMX); a camera read is
 *    ignored while a scan is in flight and the same text is ignored for data-repeat-ignore-ms (one QR stays in view
 *    for many frames);
 *  - the answer's HX-Trigger "scanResult" flashes the screen green / amber / red, plays a WebAudio tone (no audio
 *    files) and vibrates; the camera stays paused until "Scan next", so the volunteer reads the result;
 *  - the device id is made once and kept in localStorage (the scan log tells devices apart; P7's offline queue too);
 *  - no camera (no permission, not a secure context, no device): the manual tab opens and says why.
 */
(function () {
    'use strict';
    const root = document.querySelector('main.scanner');
    const form = document.getElementById('scan-form');
    if (!root || !form || !document.getElementById('gate')) {
        return; // no gates: nothing to scan
    }
    const repeatIgnoreMs = parseInt(root.dataset.repeatIgnoreMs, 10) || 3000;
    const result = document.getElementById('scan-result');
    const flash = document.getElementById('flashscreen');
    const msg = document.getElementById('camera-msg');
    const cameraPanel = document.getElementById('camera-panel');
    const manualPanel = document.getElementById('manual-panel');
    const tabs = document.getElementById('scan-tabs');
    const countEl = document.getElementById('scan-count');
    const online = document.getElementById('online');

    // ---- device id and remembered choices --------------------------------------------------------------------
    function store(key, value) {
        try {
            if (value === undefined) {
                return window.localStorage.getItem(key);
            }
            window.localStorage.setItem(key, value);
        } catch (e) {
            return null;
        }
        return value;
    }
    let deviceId = store('mitpass-device');
    if (!deviceId || !/^[A-Za-z0-9-]{4,40}$/.test(deviceId)) {
        const bytes = new Uint8Array(6);
        (window.crypto || window.msCrypto).getRandomValues(bytes);
        deviceId = 'web-' + Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
        store('mitpass-device', deviceId);
    }
    document.getElementById('device-id').value = deviceId;

    let count = 0;
    try {
        count = parseInt(window.sessionStorage.getItem('mitpass-scan-count'), 10) || 0;
    } catch (e) { /* private mode: start at 0 */ }
    countEl.textContent = String(count);

    function values(extra) {
        const mode = form.querySelector('input[name=mode]:checked');
        return Object.assign({
            gateId: document.getElementById('gate').value,
            mode: mode ? mode.value : 'IN',
            deviceId: deviceId,
        }, extra);
    }

    // ---- online indicator ------------------------------------------------------------------------------------
    function showOnline() {
        const on = navigator.onLine;
        online.classList.toggle('online--off', !on);
        online.textContent = on ? online.dataset.on || online.textContent : (online.dataset.off || 'Offline');
    }
    online.dataset.on = online.textContent;
    online.dataset.off = root.dataset.offlineLabel || 'Offline';
    window.addEventListener('online', showOnline);
    window.addEventListener('offline', showOnline);

    // ---- feedback: flash, tone, vibration --------------------------------------------------------------------
    let audio = null;
    function tone(color) {
        try {
            audio = audio || new (window.AudioContext || window.webkitAudioContext)();
            const notes = color === 'green' ? [880, 1320] : color === 'amber' ? [660, 660] : [220, 180];
            notes.forEach(function (freq, i) {
                const osc = audio.createOscillator();
                const gain = audio.createGain();
                const start = audio.currentTime + i * 0.14;
                osc.type = color === 'green' ? 'sine' : 'square';
                osc.frequency.value = freq;
                gain.gain.setValueAtTime(0.0001, start);
                gain.gain.exponentialRampToValueAtTime(0.25, start + 0.01);
                gain.gain.exponentialRampToValueAtTime(0.0001, start + 0.12);
                osc.connect(gain).connect(audio.destination);
                osc.start(start);
                osc.stop(start + 0.13);
            });
        } catch (e) { /* no audio: the flash and the card are enough */ }
    }
    function feedback(color) {
        flash.className = 'flashscreen flashscreen--' + color + ' is-on';
        setTimeout(function () { flash.classList.remove('is-on'); }, 120);
        tone(color);
        if (navigator.vibrate) {
            navigator.vibrate(color === 'green' ? 80 : color === 'amber' ? [60, 60, 60] : [200, 100, 200]);
        }
        if (color === 'green') {
            count += 1;
            countEl.textContent = String(count);
            try { window.sessionStorage.setItem('mitpass-scan-count', String(count)); } catch (e) { /* ignore */ }
        }
    }
    document.body.addEventListener('scanResult', function (event) {
        feedback((event.detail && event.detail.color) || 'red');
    });

    // ---- camera ----------------------------------------------------------------------------------------------
    let reader = null;
    let busy = false;
    let last = '';
    let lastAt = 0;

    function onRead(text) {
        const now = Date.now();
        if (busy || (text === last && now - lastAt < repeatIgnoreMs)) {
            return;
        }
        busy = true;
        last = text;
        lastAt = now;
        try { reader.pause(true); } catch (e) { /* already paused */ }
        htmx.ajax('POST', form.getAttribute('data-submit') || '/scan/submit', {
            target: '#scan-result', swap: 'innerHTML', values: values({ token: text }),
        });
    }

    function startCamera() {
        if (!window.isSecureContext || !navigator.mediaDevices || typeof Html5Qrcode === 'undefined') {
            return noCamera(root.dataset.noSecure || 'The camera needs HTTPS. Type the pass number instead.');
        }
        reader = new Html5Qrcode('reader', { verbose: false });
        reader.start(
            { facingMode: 'environment' },
            {
                fps: 10,
                qrbox: function (w, h) {
                    const side = Math.floor(Math.min(w, h) * 0.72);
                    return { width: side, height: side };
                },
                aspectRatio: 1,
            },
            onRead,
            function () { /* no code in this frame */ }
        ).catch(function () {
            noCamera(root.dataset.noCamera || 'No camera available. Type the pass number instead.');
        });
    }

    function noCamera(reason) {
        show('manual');
        tabs.querySelector('[data-tab=camera]').disabled = true;
        if (msg) {
            msg.textContent = reason;
        }
        const note = document.createElement('p');
        note.className = 'hint';
        note.textContent = reason;
        manualPanel.prepend(note);
    }

    function next() {
        result.innerHTML = '';
        busy = false;
        if (reader) {
            try { reader.resume(); } catch (e) { /* not paused */ }
        }
        const code = document.getElementById('code');
        if (code && !manualPanel.hidden) {
            code.value = '';
            code.focus();
        }
    }
    result.addEventListener('click', function (event) {
        if (event.target.closest('[data-scan-next]')) {
            event.preventDefault(); // without JavaScript it is a plain link back to /scan
            next();
        }
    });

    // ---- tabs ------------------------------------------------------------------------------------------------
    function show(which) {
        cameraPanel.hidden = which !== 'camera';
        manualPanel.hidden = which !== 'manual';
        tabs.querySelectorAll('[data-tab]').forEach(function (b) {
            b.setAttribute('aria-selected', String(b.dataset.tab === which));
        });
        if (which === 'manual') {
            busy = false;
            if (reader) { try { reader.pause(true); } catch (e) { /* ignore */ } }
        } else if (reader) {
            try { reader.resume(); } catch (e) { /* ignore */ }
        }
    }
    tabs.addEventListener('click', function (event) {
        const tab = event.target.closest('[data-tab]');
        if (tab && !tab.disabled) {
            show(tab.dataset.tab);
            if (tab.dataset.tab === 'camera' && !reader) {
                startCamera();
            }
        }
    });

    // Remember gate and mode on this device
    const gate = document.getElementById('gate');
    const savedGate = store('mitpass-gate');
    if (savedGate && gate.querySelector('option[value="' + savedGate + '"]')) {
        gate.value = savedGate;
    }
    gate.addEventListener('change', function () { store('mitpass-gate', gate.value); });

    // With JavaScript: tabs on, camera first
    tabs.hidden = false;
    show('camera');
    showOnline();
    startCamera();
})();
