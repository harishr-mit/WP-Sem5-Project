/*
 * Live dashboard (screen 7, P7). Follows /topic/events/{id} on the portal's own /portal-ws (the server relays
 * checkin-service's feed: no token in the browser) and redraws on every snapshot. Each frame is the whole snapshot,
 * so nothing is merged here. While the socket is down it polls live.json; a {"reconnect": true} frame (the relay's
 * token refresh) reconnects at once.
 */
(function () {
    'use strict';
    var root = document.querySelector('[data-live-event]');
    if (!root) { return; }
    var eventId = root.getAttribute('data-live-event');
    var pollMs = Number(root.getAttribute('data-poll') || 5) * 1000;
    var status = document.getElementById('live-status');
    var words = {};
    document.querySelectorAll('#live-words [data-code]').forEach(function (li) {
        words[li.getAttribute('data-code')] = li.textContent;
    });
    function el(id) { return document.getElementById(id); }

    function time(iso) {
        return new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: false });
    }

    function count(node, to) {
        var from = Number(node.textContent) || 0;
        if (from === to || window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
            node.textContent = to;
            return;
        }
        var start = performance.now();
        function step(now) {
            var k = Math.min(1, (now - start) / 400);
            node.textContent = Math.round(from + (to - from) * k);
            if (k < 1) { requestAnimationFrame(step); }
        }
        requestAnimationFrame(step);
    }

    var chart = null;
    function drawChart(arrivals) {
        if (!window.Chart) { return; }
        var labels = arrivals.map(function (b) { return time(b.start); });
        var data = arrivals.map(function (b) { return b.count; });
        if (chart) {
            chart.data.labels = labels;
            chart.data.datasets[0].data = data;
            chart.update();
            return;
        }
        var css = getComputedStyle(document.documentElement);
        chart = new window.Chart(el('live-chart'), {
            type: 'bar',
            data: { labels: labels, datasets: [{ data: data, backgroundColor: css.getPropertyValue('--teal').trim() }] },
            options: {
                maintainAspectRatio: false,
                animation: { duration: 300 },
                plugins: { legend: { display: false } },
                scales: { y: { beginAtZero: true, ticks: { precision: 0 } } }
            }
        });
    }

    function render(s) {
        count(el('live-checked'), s.checkedIn);
        el('live-confirmed').textContent = s.confirmed;
        count(el('live-inside'), s.inside);
        el('live-notyet').textContent = Math.max(0, s.confirmed - s.checkedIn);
        var pct = s.capacity ? Math.min(100, Math.round(s.inside * 100 / s.capacity)) : 0;
        el('live-capacity').textContent = s.capacity == null ? '—' : s.capacity;
        el('live-gauge-fill').style.width = pct + '%';
        el('live-gauge').setAttribute('aria-valuenow', pct);

        var max = s.gates.reduce(function (m, g) { return Math.max(m, g.checkedIn); }, 0);
        var gates = el('live-gates');
        gates.replaceChildren.apply(gates, s.gates.map(function (g) {
            var row = document.createElement('div');
            row.className = 'bars__row';
            var code = document.createElement('span');
            code.className = 'mono';
            code.textContent = g.code;
            var bar = document.createElement('span');
            bar.className = 'bars__bar';
            var fill = document.createElement('span');
            fill.style.width = (max ? g.checkedIn * 100 / max : 0) + '%';
            bar.appendChild(fill);
            var n = document.createElement('span');
            n.className = 'right';
            n.textContent = g.checkedIn;
            row.append(code, bar, n);
            return row;
        }));
        el('live-nogates').hidden = s.gates.length > 0;

        var list = el('live-recent');
        var newest = list.firstElementChild ? list.firstElementChild.getAttribute('data-at') : null;
        list.replaceChildren.apply(list, s.recent.map(function (r) {
            var li = document.createElement('li');
            li.setAttribute('data-at', r.at);
            if (newest && r.at > newest) { li.className = 'is-new'; }
            var at = document.createElement('span');
            at.className = 'mono';
            at.textContent = time(r.at);
            var who = document.createElement('strong');
            who.textContent = r.firstName || words._someone;
            var gate = document.createElement('span');
            gate.className = 'mono hint';
            gate.textContent = r.gateCode || '';
            var word = document.createElement('span');
            word.className = 'badge ' + (r.color === 'GREEN' ? 'badge--ok' : r.color === 'AMBER' ? 'badge--warn' : 'badge--err');
            word.textContent = words[r.code] || r.code;
            li.append(at, who, gate, word);
            return li;
        }));
        el('live-noscans').hidden = s.recent.length > 0;
        drawChart(s.arrivals);
    }

    function setLive(on) {
        status.textContent = status.getAttribute(on ? 'data-live' : 'data-reconnecting');
        status.className = 'badge ' + (on ? 'badge--info badge--live' : 'badge--warn');
    }

    var poller = null;
    function poll() {
        fetch('/organizer/events/' + eventId + '/live.json', { headers: { Accept: 'application/json' } })
            .then(function (r) { return r.ok ? r.json() : null; })
            .then(function (s) { if (s) { render(s); } })
            .catch(function () { /* still down; try again on the next tick */ });
    }
    function startPolling() { if (!poller) { poller = setInterval(poll, pollMs); } }
    function stopPolling() { if (poller) { clearInterval(poller); poller = null; } }

    poll(); // the chart needs the data; the rest is already on the page

    if (window.StompJs) {
        var client = new window.StompJs.Client({
            brokerURL: (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/portal-ws',
            reconnectDelay: 3000
        });
        client.onConnect = function () {
            setLive(true);
            stopPolling();
            client.subscribe('/topic/events/' + eventId, function (frame) {
                var body = JSON.parse(frame.body);
                if (body.reconnect) {
                    // the relay closed the upstream (token refresh, or the feed is down): a new handshake gets a fresh
                    // token. Wait first and poll meanwhile, so a feed that keeps failing can't become a reconnect storm.
                    setLive(false);
                    startPolling();
                    client.deactivate().then(function () { setTimeout(function () { client.activate(); }, pollMs); });
                    return;
                }
                render(body);
            });
        };
        client.onWebSocketClose = function () { setLive(false); startPolling(); };
        client.onStompError = function () { setLive(false); startPolling(); };
        client.activate();
    } else {
        startPolling();
    }

    var full = el('live-full');
    if (document.fullscreenEnabled) {
        full.hidden = false;
        full.addEventListener('click', function () {
            if (document.fullscreenElement) { document.exitFullscreen(); } else { root.requestFullscreen(); }
        });
    }
})();
