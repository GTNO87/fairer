# Fairer



Fairer is my attempt at a free, open-source Android app that protects you from predatory commercial data practices — the kind that result in you being charged more than someone else for the same product, or shown different prices based on what algorithms have inferred about your willingness to pay.

---

## What it does

Fairer runs a local VPN on your phone that intercepts DNS queries — the requests your phone makes to look up website addresses. When an app or website tries to contact a known commercial manipulation platform, Fairer blocks that request before it can reach the server. Everything else passes through normally.

Blocked categories include:

- **Dynamic pricing engines** — software that automatically adjusts prices based on who you are and how much it thinks you'll pay (used by Pricefx, Revionics, Zilliant, Vendavo, PROS, and others)
- **A/B testing and personalisation platforms** — tools that show different users different versions of a page, often to test which price or offer extracts the most money (Optimizely, Adobe Target, Dynamic Yield, Monetate, and others)
- **Data brokers** — companies that compile and sell detailed profiles of your behaviour and finances to retailers and insurers (Acxiom, LiveRamp, Oracle Data Cloud, Experian Marketing, and others)
- **Insurance pricing data feeds** — specialist data suppliers that feed risk-scoring models used to price car, home, and health insurance (LexisNexis Risk Solutions, Verisk, CCC, Solera, and others)

---

## Why it exists

Retailers, insurers, and online platforms are increasingly using personal data and algorithmic pricing to charge people different amounts for the same thing. This is legal in most places, largely invisible, and growing fast.

Fairer does not fix this problem. But it makes it harder for these platforms to gather the data they need to do it to you.

---

## How it works

1. When you turn Fairer on, it creates a local VPN tunnel on your device. No traffic leaves your device through a remote server — the VPN runs entirely on your phone.
2. Your phone's DNS traffic (domain lookups) is routed through the tunnel to Fairer.
3. Fairer checks each domain against its blocklist. Blocked domains get an NXDOMAIN response (the equivalent of "that address doesn't exist"). This prevents the app or website from connecting to the tracking platform.
4. All other DNS queries are forwarded encrypted to [Cloudflare's 1.1.1.1](https://1.1.1.1) using **DNS-over-HTTPS**, so your ISP cannot see which domains you're looking up.
5. The main screen shows a running count of tracking requests blocked today.

Your browsing traffic itself is not inspected or routed through Fairer — only DNS queries.

---

## Building the app

### Requirements

- Android Studio Hedgehog or later
- Android SDK 34
- JDK 17
- A device or emulator running Android 8.0 (API 26) or higher

### Steps

1. Clone the repository:
   ```
   git clone https://github.com/your-org/fairer.git
   cd fairer
   ```

2. Open the project in Android Studio (`File > Open`, select the `Fairer` folder).

3. Let Gradle sync. No additional configuration is required.

4. Build and run:
   - **Debug build:** press the Run button in Android Studio, or run `./gradlew assembleDebug` from the command line.
   - **Release build:** configure a signing key in `keystore.properties` (see Android documentation), then run `./gradlew assembleRelease`.

5. When the app launches, grant the VPN permission when prompted, then toggle the switch to activate protection.

---

## The blocklist

The blocklist lives in [`app/src/main/assets/blocklists/manipulation-blocklist.txt`](app/src/main/assets/blocklists/manipulation-blocklist.txt). It is a plain-text hosts file — one domain per line, with `0.0.0.0` in front of each entry and `#` for comments.

**Contributions are welcome.** If you know of a dynamic pricing vendor, data broker, insurance data supplier, or personalisation platform that isn't on the list, please open a pull request or an issue. When proposing an addition, please include a brief note explaining what the company does and why it belongs in the list — this keeps the blocklist focused and trustworthy.

The blocklist deliberately does not block general-purpose advertising or tracking (there are excellent tools for that already). Its scope is specifically commercial manipulation: platforms whose primary purpose is to enable differential pricing, risk scoring, or behavioural profiling that affects what you are charged.

---

## Permissions

Fairer requests only the permissions it needs:

| Permission | Why |
|---|---|
| `INTERNET` | To forward DNS queries to Cloudflare |
| `FOREGROUND_SERVICE` | Required to keep the VPN running while you use your phone |
| `BIND_VPN_SERVICE` | Required for Android VPN functionality |
| `POST_NOTIFICATIONS` | To show the persistent notification that the VPN is active |

Fairer does not request access to contacts, location, camera, storage, or any other personal data.

---

## Privacy

- No data leaves your device except the DNS queries forwarded to Cloudflare over HTTPS.
- No analytics, no telemetry, no remote logging.
- The blocked-requests counter is stored locally and reset each day.
- Cloudflare's 1.1.1.1 privacy policy applies to forwarded DNS queries: [https://1.1.1.1](https://1.1.1.1).

---

## Licence

[MIT](LICENSE)
