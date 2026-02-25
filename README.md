# fairer

** [fairer.gtno87.io](https://gtno87.github.io/fairer) · Free · Open source · Android

Fairer is a free, open-source Android app that protects you from the hidden commercial data infrastructure that tracks your behaviour, builds profiles about you. It works at the DNS level — blocking tracking and manipulation platforms before they can exchange a single byte with your device.

---

## What it blocks

Fairer maintains a curated blocklist of domains used by commercial manipulation and tracking platforms, merged with six community-maintained lists at update time. Together they cover six categories of threat:

**Dynamic pricing engines**
Software that adjusts prices in real time based on inferences about your willingness to pay. Includes Pricefx, Revionics, Zilliant, Vendavo, PROS, and others.

**A/B testing and personalisation platforms**
Tools that show different users different prices or offers based on behavioural profiles built without their knowledge. Includes Optimizely, Adobe Target, Dynamic Yield, Monetate, and others.

**Data brokers**
Companies that compile detailed profiles of your behaviour, finances, and demographics and sell them to retailers, insurers, and advertisers. Includes Acxiom, LiveRamp, Oracle Data Cloud, Experian Marketing, and others.

**Cross-site trackers**
Ad networks and analytics platforms that follow you across every app and website you use. Includes Google, Facebook, and hundreds of others tracked by Disconnect.me and EasyPrivacy.

**Device telemetry**
Endpoints used by Android and device manufacturers to collect usage data about how you use your phone. Covers Samsung, Xiaomi, Huawei, OPPO, and other OEM telemetry infrastructure.

**Social media trackers**
Facebook, Twitter, and LinkedIn tracking pixels and beacons embedded across millions of third-party websites — following you when you're not using their own platforms.

---

## How it works

Fairer creates a local VPN tunnel on your device. No traffic is routed through a remote server — the VPN runs entirely on your phone.

1. **DNS interception.** Every app on your phone makes a DNS query before loading content. Fairer routes these queries through the local tunnel.
2. **Blocklist lookup.** Each queried domain is checked against an in-memory HashSet of blocked domains. If it matches, Fairer returns an NXDOMAIN response (the DNS equivalent of "this address does not exist"). The connection never happens.
3. **DNS-over-HTTPS forwarding.** Queries that are not blocked are forwarded encrypted to [Cloudflare 1.1.1.1](https://1.1.1.1) via DNS-over-HTTPS, so your ISP cannot see which domains you look up.
4. **App attribution.** When a domain is blocked, Fairer reads `/proc/net/udp` to identify which app made the request and logs it to the in-session block log.

Your browsing traffic is never inspected or routed through Fairer — only DNS queries.

---

## Blocklist sources

Fairer merges six sources at update time. All duplicates are removed before loading into memory.

| Source | What it covers | Licence |
|---|---|---|
| **Fairer Manipulation Blocklist** | Dynamic pricing engines, A/B testing platforms, data brokers, insurance data feeds — curated specifically for commercial manipulation | Ed25519 signed; see [Contributing](#contributing-to-the-blocklist) |
| **Disconnect.me Simple Tracking** | Cross-site tracking networks | GPL v3 |
| **Hagezi Pro** | Broad tracking, ads, and telemetry across the web and apps | MIT |
| **Hagezi Native Tracker** | Tracking built into Android and device manufacturer firmware (Samsung, Xiaomi, Huawei, OPPO, and others) | MIT |
| **EasyPrivacy** | Tracking scripts, pixels, and beacons across the web | GPL v3 |
| **Fanboy's Social Blocking List** | Facebook, Twitter, and LinkedIn trackers embedded on third-party sites | CC BY 3.0 |

The Fairer unique list is cryptographically signed with Ed25519. The app verifies the signature before accepting any update to it. Community lists are fetched over HTTPS and do not require signature verification; a failed download skips that source without aborting the update.

---

## Security

Fairer was security audited covering:

- Response size caps on all HTTP downloads (5 MB for the Fairer list, 50 MB for community hosts files, 64 KB for DoH responses) to prevent OOM from oversized or malicious server responses
- Atomic blocklist file writes via `.tmp` + rename, so a crash during a download never leaves a corrupt cached copy on disk
- Ed25519 signature verification on the Fairer unique list — MITM cannot inject domains even without certificate pinning
- Thread safety across the VPN loop, DNS executor pool, and blocklist load path
- FileProvider scoped exclusively to `cacheDir/exports/` for log sharing — no other app data is reachable through it
- Minimal permissions — no storage, location, camera, or contacts access

All findings from the audit were addressed before any user installed the app.

---

## Building and installing

### Requirements

- Android Studio Hedgehog or later
- Android SDK 34
- JDK 17
- A device or emulator running Android 8.0 (API 26) or higher

### Steps

1. Clone the repository:
   ```
   git clone https://github.com/GTNO87/fairer.git
   cd fairer
   ```

2. Open the project in Android Studio (`File > Open`, select the `Fairer` folder).

3. Let Gradle sync. No additional configuration is required.

4. Build and run:
   - **Debug:** press Run in Android Studio, or `./gradlew assembleDebug`
   - **Release:** configure a signing key in `keystore.properties` (see Android documentation), then `./gradlew assembleRelease`

5. When the app launches, grant the VPN permission when prompted, then tap the power button to activate protection.

---

## Contributing to the blocklist

The Fairer unique blocklist lives in [`app/src/main/assets/blocklists/manipulation-blocklist.txt`](app/src/main/assets/blocklists/manipulation-blocklist.txt). It is a plain-text hosts file — one domain per line in `0.0.0.0 domain.com` format, with `#` for comments.

**To propose an addition**, open a pull request or issue. Please include:
- The company or platform name
- What it does (dynamic pricing, A/B testing, data brokering, etc.)
- The domains it uses
- A source — a news article, privacy policy, or technical reference that confirms the purpose

The Fairer list deliberately does not duplicate what the community lists cover (general tracking, ads). Its scope is specifically commercial manipulation: platforms whose primary function is differential pricing, behavioural profiling for commercial gain, or selling data that affects what you are charged.

After any change to the blocklist, the file must be re-signed with the Ed25519 private key. See the signing workflow in `.github/workflows/`.

---

## Permissions

| Permission | Reason |
|---|---|
| `INTERNET` | Forward unblocked DNS queries to Cloudflare, and download blocklist updates |
| `FOREGROUND_SERVICE` | Keep the VPN running while you use your phone |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Required on Android 14+ for VPN foreground services |
| `BIND_VPN_SERVICE` | Required for Android VPN functionality |
| `POST_NOTIFICATIONS` | Show the persistent notification that protection is active |

Fairer does not request access to contacts, location, camera, microphone, or storage.

---

## Privacy

- No analytics, telemetry, crash reporters, or remote logging of any kind.
- No data leaves your device except DNS queries forwarded to Cloudflare over HTTPS.
- The block log is stored in memory only and is cleared when the app is closed.
- Cloudflare's privacy policy applies to forwarded DNS queries: [https://1.1.1.1/en-GB/privacy-policy/](https://1.1.1.1/en-GB/privacy-policy/)

---

## Licence

[GPL-3.0](LICENSE)
