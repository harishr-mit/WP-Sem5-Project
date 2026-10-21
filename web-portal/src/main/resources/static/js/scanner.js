/*
 * The volunteer's scanner (UI reference screens 5–6; docs/plans/phase-6.md §8). Without this file the page still works
 * (the manual form posts to /scan). With it:
 *  - the camera tab reads QR codes with html5-qrcode and posts each one to /scan/submit (HTMX); a camera read is
 *    ignored while a scan is in flight and the same text is ignored for data-repeat-ignore-ms (one QR stays in view
 *    for many frames);
 *  - the answer's HX-Trigger "scanResult" flashes the screen green / amber / red, plays a WebAudio tone (no audio
 *    files) and vibrates; the camera stays paused until "Scan next", so the volunteer reads the result;
 *  - the device id is made once and kept in localStorage (the scan log tells devices apart; P7's offline queue too);
 *  - no camera (no permission, not a secure context, no device): the manual tab opens and says why;
 *  - P7: a scan that gets no answer within data-scan-timeout-ms (or a network error) switches to offline mode
 *    (offline.js): the phone checks the pass itself, queues it, and the answer is drawn here; check-out is locked.
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
    const offline = window.MitpassOffline || null;
    if (window.htmx) {
        htmx.config.timeout = parseInt(root.dataset.scanTimeoutMs, 10) || 4000;
    }

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
    if (offline) {
        offline.deviceId = deviceId;
    }

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

    // ---- offline mode (P7; the online badge belongs to offline.js) -------------------------------------------
    let pending = null; // the text of the scan in flight, to check offline if it gets no answer
    const outRadio = form.querySelector('input[name=mode][value=OUT]');
    const inRadio = form.querySelector('input[name=mode][value=IN]');
    function offlineChanged(isOffline) {
        if (outRadio) {
            outRadio.disabled = isOffline; // check-out needs the network (owner Q3)
            outRadio.title = isOffline ? (root.dataset.outNeedsNetwork || '') : '';
            if (isOffline && inRadio) {
                inRadio.checked = true;
            }
        }
    }
    if (offline) {
        offline.onChange(offlineChanged);
        offlineChanged(offline.isOffline());
    }

    function renderLocal(r) {
        const card = document.createElement('div');
        card.className = 'result result--' + r.color;
        const word = document.createElement('p');
        word.className = 'result__word';
        word.textContent = r.word;
        const message = document.createElement('p');
        message.className = 'result__msg';
        message.textContent = r.message;
        card.append(word, message);
        if (r.firstName || r.passNumber) {
            const who = document.createElement('div');
            who.className = 'result__who';
            const name = document.createElement('p');
            const strong = document.createElement('strong');
            strong.textContent = r.firstName || '';
            name.appendChild(strong);
            const number = document.createElement('p');
            number.className = 'pass-number';
            number.textContent = r.passNumber || '';
            who.append(name, number);
            card.appendChild(who);
        }
        const next = document.createElement('a');
        next.className = 'btn';
        next.href = '/scan';
        next.setAttribute('data-scan-next', '');
        next.textContent = root.dataset.nextLabel || 'Scan next';
        card.appendChild(next);
        result.replaceChildren(card);
        feedback(r.color);
    }

    function offlineRead(text) {
        busy = true;
        if (reader) { try { reader.pause(true); } catch (e) { /* already paused */ } }
        offline.scan(text).then(renderLocal);
    }

    // No answer from the portal: the network is gone. Check the scan in flight on this phone instead.
    function lostNetwork() {
        if (!offline || pending === null) {
            return;
        }
        const text = pending;
        pending = null;
        offline.goOffline();
        offlineRead(text);
    }
    document.body.addEventListener('htmx:sendError', lostNetwork);
    document.body.addEventListener('htmx:timeout', lostNetwork);
    document.body.addEventListener('htmx:afterRequest', function (event) {
        if (event.detail && event.detail.successful) {
            pending = null;
        }
    });
    // The manual tab while offline: check the typed number (or pasted code) here instead of asking the server.
    document.body.addEventListener('htmx:beforeRequest', function (event) {
        const code = document.getElementById('code');
        const detail = event.detail || {};
        const path = (detail.pathInfo && detail.pathInfo.requestPath) || (detail.requestConfig && detail.requestConfig.path) || '';
        if (path && path.indexOf('/scan/check') >= 0 && code) {
            pending = code.value;
            if (offline && offline.isOffline()) {
                event.preventDefault();
                pending = null;
                offlineRead(code.value);
            }
        }
    });

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
        last = text;
        lastAt = now;
        if (offline && offline.isOffline()) {
            offlineRead(text);
            return;
        }
        busy = true;
        pending = text;
        try { reader.pause(true); } catch (e) { /* already paused */ }
        htmx.ajax('POST', form.getAttribute('data-submit') || '/scan/submit', {
            target: '#scan-result', swap: 'innerHTML', values: values({ token: text }),
        });
    }

    function startCamera() {
        if (!window.isSecureContext || !navigator.mediaDevices || typeof Html5Qrcode === 'undefined') {
            return noCamera(root.dataset.noSecure || 'The camera needs HTTPS. Type the pass number instead.');
        }
        // Found in the two-phone demo (2026-10-19): a pass code read off another phone's screen came out blurry. So: the
        // browser's own detector where there is one (Chrome on Android; far better with dense codes), QR codes only,
        // a sharp image (high resolution, continuous focus) and zoom, so the phone can stay far enough away to focus.
        reader = new Html5Qrcode('reader', {
            verbose: false,
            formatsToSupport: [Html5QrcodeSupportedFormats.QR_CODE],
            experimentalFeatures: { useBarCodeDetectorIfSupported: true },
        });
        const scanConfig = {
            fps: 12,
            aspectRatio: 1,
            qrbox: function (w, h) {
                const side = Math.floor(Math.min(w, h) * 0.8);
                return { width: side, height: side };
            },
        };
        const sharp = Object.assign({}, scanConfig, {
            videoConstraints: {
                facingMode: 'environment',
                // Square, like the viewfinder: html5-qrcode maps its scan box as if the video kept its own shape, so
                // a 16:9 stream cropped by object-fit put the box (and its brackets) on the wrong part of the frame.
                aspectRatio: { ideal: 1 },
                width: { ideal: 1080 },
                height: { ideal: 1080 },
                advanced: [{ focusMode: 'continuous' }],
            },
        });
        reader.start({ facingMode: 'environment' }, sharp, onRead, function () { /* no code in this frame */ })
            .catch(function () {
                // A camera that refuses the sharp settings: try once with the plain ones.
                return reader.start({ facingMode: 'environment' }, scanConfig, onRead, function () { /* none */ });
            })
            .then(offerZoom)
            .then(nativeDetector)
            .catch(function () {
                noCamera(root.dataset.noCamera || 'No camera available. Type the pass number instead.');
            });
    }

    // html5-qrcode shrinks each frame to the scan box's on-screen size (~270 px) before decoding, so a code filling less
    // than ~80 % of the box had under a pixel per module. Where the browser has its own detector (Chrome on Android),
    // it also reads the video element itself, at the camera's full resolution; html5-qrcode stays the fallback.
    function nativeDetector() {
        if (!('BarcodeDetector' in window)) {
            return;
        }
        window.BarcodeDetector.getSupportedFormats().then(function (formats) {
            if (formats.indexOf('qr_code') < 0) {
                return;
            }
            const detector = new window.BarcodeDetector({ formats: ['qr_code'] });
            const video = document.querySelector('#reader video');
            function loop() {
                if (!video || video.readyState < 2 || busy || cameraPanel.hidden) {
                    setTimeout(loop, 150);
                    return;
                }
                detector.detect(video)
                    .then(function (codes) { if (codes.length) { onRead(codes[0].rawValue); } })
                    .catch(function () { /* a frame it couldn't read */ })
                    .then(function () { setTimeout(loop, 100); });
            }
            loop();
        }).catch(function () { /* no native detector: html5-qrcode alone */ });
    }

    // Zoom where the camera supports it (most Android phones). It starts slightly zoomed in, so the volunteer holds
    // the phone back, where its camera can focus, instead of up against the pass.
    function offerZoom() {
        const row = document.getElementById('camera-zoom-row');
        const slider = document.getElementById('camera-zoom');
        let caps = null;
        try { caps = reader.getRunningTrackCapabilities(); } catch (e) { return; }
        if (caps && caps.focusMode && caps.focusMode.indexOf('continuous') >= 0) {
            reader.applyVideoConstraints({ advanced: [{ focusMode: 'continuous' }] }).catch(function () { /* fine */ });
        }
        if (!row || !slider || !caps || !caps.zoom || !(caps.zoom.max > caps.zoom.min)) {
            return;
        }
        slider.min = String(caps.zoom.min);
        slider.max = String(Math.min(caps.zoom.max, 5));
        slider.step = String(caps.zoom.step || 0.1);
        const start = Math.min(Math.max(1.5, caps.zoom.min), Number(slider.max));
        slider.value = String(start);
        function apply() {
            reader.applyVideoConstraints({ advanced: [{ zoom: Number(slider.value) }] }).catch(function () { /* ignore */ });
        }
        slider.addEventListener('input', apply);
        apply();
        row.dataset.ready = 'true';
        row.hidden = cameraPanel.hidden;
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
        const zoomRow = document.getElementById('camera-zoom-row');
        if (zoomRow && zoomRow.dataset.ready) {
            zoomRow.hidden = which !== 'camera';
        }
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
    startCamera();
})();
