/*
 * Portal-wide behaviour (docs/plans/phase-6.md §4). Every page works without this file; it only makes things nicer:
 *  - HTMX requests carry the CSRF token from the <meta> tags (Spring Security refuses a POST without it).
 *  - A 422 answer (a form with validation errors) is swapped in like a 200, so the errors appear in place.
 *  - [data-open-dialog="id"] opens that <dialog> instead of following the link or submitting; [data-close-dialog]
 *    closes it. The dialog's own form still posts confirmed=true, and the server still checks it (RL9).
 *  - HX-Trigger "toast" messages show as a flash at the top of the page.
 */
(function () {
    'use strict';

    function meta(name) {
        const el = document.querySelector('meta[name="' + name + '"]');
        return el ? el.content : null;
    }

    if (window.htmx) {
        htmx.config.responseHandling = [
            { code: '204', swap: false },
            { code: '[23]..', swap: true },
            { code: '422', swap: true },
            { code: '[45]..', swap: false, error: true }
        ];
        htmx.config.defaultSwapStyle = 'outerHTML';
        htmx.config.historyCacheSize = 0; // pages hold personal data; don't keep copies in localStorage
        htmx.config.includeIndicatorStyles = false; // portal.css has them (and a strict CSP later won't allow inline)

        document.addEventListener('htmx:configRequest', function (event) {
            const token = meta('_csrf');
            const header = meta('_csrf_header');
            if (token && header) {
                event.detail.headers[header] = token;
            }
        });

        // A failed HTMX request (5xx, or the gateway down) shouldn't fail silently.
        document.addEventListener('htmx:responseError', function (event) {
            const status = event.detail.xhr ? event.detail.xhr.status : 0;
            toast(status >= 500 || status === 0
                ? document.documentElement.dataset.msgUnavailable || 'Something went wrong. Please try again.'
                : document.documentElement.dataset.msgFailed || 'That didn\'t work. Please reload the page.', 'err');
        });
        document.addEventListener('htmx:sendError', function () {
            toast(document.documentElement.dataset.msgOffline || 'No connection. Check your network.', 'err');
        });
        document.addEventListener('toast', function (event) {
            if (event.detail && event.detail.value) {
                toast(event.detail.value, event.detail.kind || 'ok');
            }
        });
    }

    document.addEventListener('click', function (event) {
        const opener = event.target.closest('[data-open-dialog]');
        if (opener) {
            const dialog = document.getElementById(opener.dataset.openDialog);
            if (dialog && typeof dialog.showModal === 'function') {
                event.preventDefault();
                dialog.showModal();
                const first = dialog.querySelector('input:not([type=hidden]), button');
                if (first) {
                    first.focus();
                }
            }
            return;
        }
        const closer = event.target.closest('[data-close-dialog]');
        if (closer) {
            event.preventDefault();
            const dialog = closer.closest('dialog');
            if (dialog) {
                dialog.close();
            }
        }
    });

    function toast(text, kind) {
        const main = document.getElementById('main');
        if (!main) {
            return;
        }
        const box = document.createElement('div');
        box.className = 'flash flash--' + (kind === 'err' ? 'err' : 'ok');
        box.setAttribute('role', kind === 'err' ? 'alert' : 'status');
        const p = document.createElement('p');
        p.textContent = text;
        box.appendChild(p);
        main.prepend(box);
        setTimeout(function () { box.remove(); }, 6000);
    }
    window.mitpassToast = toast;
})();
