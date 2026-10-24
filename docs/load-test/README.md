# Load test: 1,000 students arriving in 10 minutes

`load-tests/` (Gatling, Java DSL). Each virtual student logs in, finds their pass, gets the live QR code and is scanned in by one of the four gates' volunteers (40 volunteers, 10 per gate). 300 students arrive in the first minute (doors open), the other 700 over the next nine. Run with the stack from `docker compose up --build` and the bulk seed loaded:

```
./mvnw -Pload -pl load-tests gatling:test        # ARRIVALS, PEAK, MINUTES, OFFSET are environment knobs
```

Open `report/index.html` for the full Gatling report.

## Result (2026-10-24, one laptop: the whole stack, Gatling and the browser on 8 CPUs / 8 GB for Docker)

| | |
|---|---|
| Requests | 4,000 (4 per student: login, find pass, live QR, scan) |
| Failures | **0** (every scan `ACCEPTED`) |
| Response time | mean 60 ms · p50 24 ms · p75 74 ms · **p95 104 ms** · p99 833 ms · max 4.6 s |
| Under 800 ms | 99.1 % (28 requests, 0.7 %, took 1.2 s or more: the first logins while the JVMs were cold and BCrypt ran on a busy CPU) |
| Assertions | no failed request; p95 < 1.5 s: both held |

The 10-minute shape needs only about 1.7 scans per second on average (5 per second in the peak minute), which one laptop handles with room to spare; the interesting numbers are the tail (p99) and that nothing was dropped or dead-lettered. The gateway's login limit is per address, so the simulation gives every virtual student an `X-Forwarded-For` of their own (as the portal does for a browser).
