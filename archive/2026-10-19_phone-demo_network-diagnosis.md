# Phone demo: why the phones couldn't reach the portal (2026-10-19)

Phase 6, T08.13 (the two-phone exit demo). The owner's phones timed out on `https://<laptop>:8443` although the
portal answered on the laptop. Four separate causes, found one after another. The working setup and the steps for
next time are in README *Phone demo*; `scripts/phone-demo-check.ps1` checks all of it in one go.

## Setup at the start

- Laptop: Windows 11, Intel Wi-Fi 6 AX201, Windows Defender Firewall (no other firewall; McAfee only as WebAdvisor).
- Network: the laptop on a phone hotspot (`vivo 1904`, marked Private), laptop `192.168.110.21`, the vivo `192.168.110.63`,
  a second phone at about `192.168.110.117`. The hotspot also hands out public IPv6 (`2401:4900:…`).
- Portal certificate from `scripts/make-dev-cert.ps1`, SANs `localhost`, `127.0.0.1`, `192.168.110.21`.

## What we checked, in order

| # | Suspect | How it was checked | Result |
|---|---|---|---|
| 1 | Portal not in phone mode | `Get-NetTCPConnection -LocalPort 8443`, process command lines | **Cause 1.** It ran as `./mvnw -pl web-portal spring-boot:run`: http :8090 only, nothing on 8443. Maven also starts in `web-portal/`, where the certificate's relative path doesn't resolve. Fixed: `java -jar web-portal/target/web-portal.jar --spring.profiles.active=lan` from the repo root |
| 2 | Windows Firewall rules for the project's Java | `Get-NetFirewallApplicationFilter` for `.tools\jdk-21\bin\java.exe` | **Cause 2.** Two **Block** rules on Private (left by a cancelled "Allow access?" prompt). A Block rule beats every Allow rule. Also two **Allow** rules on Public (TCP and UDP, every port), which opened this Java on any public Wi-Fi. The owner removed all four |
| 3 | No port rule | `Get-NetFirewallRule -DisplayName "MITPass portal (HTTPS 8443)"` | **Cause 3.** Missing. The owner added it (Private; later limited to `LocalSubnet`; it ended up there twice, harmless) |
| 4 | Certificate doesn't name the address | `keytool -list -v` on `infra/certs/portal-dev.p12` | Fine: it named `192.168.110.21` |
| 5 | Another firewall / security product | `root/SecurityCenter2` FirewallProduct, services | Ruled out: only Windows Firewall |
| 6 | The hotspot keeps devices apart (client isolation) | The owner tried from the vivo itself (the phone sharing the hotspot) | Still a timeout, so it isn't only isolation between connected devices |
| 7 | Do the phone's packets reach the laptop at all? | Firewall logging of allowed + dropped packets on Private (admin), one phone attempt, 30 s wait, search for port 8443 | Nothing logged |
| 8 | Were we searching for the wrong thing? | The owner printed the log header and sample lines; the search was widened to any packet from any other `192.168.110.x` to the laptop | Format as assumed (`date time action protocol src-ip dst-ip src-port dst-port …`); the log grew 6.9 → 27 KB during the test, yet **not one packet from either phone** reached the laptop, on any port. **Cause 4: the phone hotspot never delivers the traffic.** Android hotspots often send their own apps' traffic out over mobile data only and keep connected devices apart, with no setting to change it |
| 9 | Laptop as the hotspot instead | Windows *Mobile hotspot* on; one phone connected (`192.168.137.108`); certificate remade (now names `192.168.137.1`); portal restarted | Still a timeout. The hotspot adapter (`Local Area Connection* 2`, Wi-Fi Direct) has **no network profile**, so the firewall treats it as **Public**, and the 8443 rule covered Private only |
| 10 | A rule for the laptop's hotspot | The owner added `MITPass portal (HTTPS 8443, laptop hotspot)`: Public, TCP 8443, only from `192.168.137.0/24` | **Works.** The phone opens the portal |

## Security findings along the way

- **Allow rules from the "Allow access?" prompt open every port** of the program, not just the one the demo needs.
  On this laptop that meant (at different times): every port of the project's Java on Public networks (removed),
  and later, after the prompt was answered with *Private*, every port on Private networks (`TCP/UDP Query User…`
  rules). On a Private network that exposes Eureka's dashboard (8761, no login), each service directly (8081–8084)
  and the portal's plain http (8090). Cancelling the prompt is no better: it creates Block rules that break the demo.
  README step 1 now removes every rule for the project's Java and adds rules tied to **that program and port 8443**.
- **Anyone who can reach the portal can try the demo accounts**: their password is in the README. Keep the portal
  off when you're not demoing, and use a hotspot password.
- Unrelated to MITPass, seen in passing: two allow-all-ports rules on Private for program files under
  `AppData\Local\Temp` (a Tencent game downloader for `com.supercell.clashofclans…exe`). The owner may delete them.

## The setup that works

1. Laptop as the Wi-Fi hotspot; phones on it with mobile data off.
2. Firewall: no rule for the project's Java except `MITPass portal (HTTPS 8443, laptop hotspot)` (Public, TCP 8443,
   only from `192.168.137.0/24`, tied to the project's Java).
3. Certificate made with the hotspot on (names `192.168.137.1`).
4. Portal started from the repo root with `--spring.profiles.active=lan`.
5. `scripts/phone-demo-check.ps1` says *Ready*; phones open `https://192.168.137.1:8443` and accept the warning once.

## Lessons

- "Opens on the laptop, times out on the phone" means the packets are dropped somewhere. Prove where with the
  firewall log before changing anything else: nothing logged means the network, a DROP line means the laptop.
- On Windows, look at *every* rule for the program, not just the port rule: one forgotten prompt answer can block it
  or open it wide.
- A phone hotspot is not a neutral network. The laptop's own hotspot is the predictable one, and Windows counts it
  as Public.
- Check the network type of the interface the phones actually arrive on. The laptop's hotspot adapter never showed up
  in `Get-NetConnectionProfile`.

## Firewall changes made during the session (all by the owner, in an administrator PowerShell)

| Change | Status at the end |
|---|---|
| Removed 2 Block rules (Private) and 2 Allow-every-port rules (Public) for the project's Java | gone |
| Added `MITPass portal (HTTPS 8443)`: Private, TCP 8443, later `-RemoteAddress LocalSubnet` (created twice) | 2 copies present; not needed for the laptop hotspot |
| Added `MITPass portal (HTTPS 8443, laptop hotspot)`: Public, TCP 8443, `192.168.137.0/24` (any program) | present; README now ties it to the project's Java too |
| New prompt answer created `TCP/UDP Query User…` Allow rules for the project's Java (Private, every port) | present; should be removed |
| Firewall logging (Private) on for the tests | turned off again |

## Follow-up the same day: the camera couldn't read the code

With the network fixed, *Type pass no.* worked but the camera scan didn't: the owner had to turn phone A's brightness
down to see the code at all, and it was still blurry. Measured, not guessed:

| Finding | Evidence | Fix (log.md T08.14) |
|---|---|---|
| The code was too dense | A live token is 467 characters; at error correction **H** that is a version-24 code, **113×113 modules**, drawn 232 px wide on the card: about 1.9 CSS px per module. Phone B had to come so close that it couldn't focus | Owner: level **M** (85×85 modules), badge 15 % instead of 20 % (PROJECT.md §P4, D12) |
| The card drew it small | 232 px, inside a card with wide margins | Up to 300 px (278 px on a 360 px phone); **tap for a full-screen white view** (331 px on a 360 px phone) |
| The scanner asked for little | No resolution, no focus mode, the JavaScript decoder only | 1080p back camera, continuous focus, the browser's own barcode detector where there is one, QR only, a **zoom** slider (starts at 1.5×) so phone B stays 20–30 cm away |
| ZXing misses some perfect codes | 300–1000 random tokens of the real length, drawn perfectly: ZXing's reader (the family html5-qrcode uses) failed on **2.4 %** with the encoder's mask, at every level (H included: 8/300); quiet zone and badge made no difference; another mask fixed **24 of 24** | pass-service reads each code back and switches mask when needed (about 4 ms) |
| Light event colours | Contrast against white below 7:1 would weaken any decoder | Modules fall back to near-black; the badge keeps the event colour |

Verified: `QrRendererTest` (150 random tokens all decode; M smaller than H; colour fallback), `PassPagesTest`,
`ScanPagesTest`, Playwright `student.mjs` 34/34 (code 278 px; full screen 331 px on white; tap and Escape close it)
and `scanner.mjs` 11/11 with a real live code through a fake camera. Brightness tip in README: about half, since full
brightness washes the code out for a camera.
