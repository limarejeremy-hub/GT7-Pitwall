# Pitwall GT7 / PSN: account-linking security and commercial readiness

## Current implementation (V9)
- Opt-in **PSN online ID**, optional nickname and official Gran Turismo profile link. This is **not** PlayStation sign-in or authentication.
- Career fields DR, SR, races, victories, poles, clean races, top 5, garage, collector level and DR-point snapshots are **manually provided or explicitly imported** from owned JSON. Their origin/date is visible.
- Strict PSN-ID matching for imported data, bounded import size (120 KB), reject recognizable credential fields, validate nonnegative counts and consistency.
- Android application-private storage; **no cookies, passwords, NPSSO, OAuth tokens, PSN emails, refresh tokens or third-party credentials**.
- Change PSN ID -> clear prior stats to prevent identity mixing.
- In-app deletion and user export; user-approved local profile is distinct from Pitwall's UDP/car/circuit archival telemetry.
- Official Sony profile URLs are allowlisted to exact HTTPS host and profile path and opened in Android browser; credentials are entered only on Sony's site.
- No automatic Sony profile scraping, bypass, background login or claimed global DR/SR sync.

## Mandatory gate for a real commercial 'Connect PSN'
1. Receive developer **permission and documented scopes** from Sony / approved PlayStation platform program.
2. Use documented **Authorization Code + PKCE** if supported by the authorization provider: system browser, verified HTTPS app link, state/nonce, no embedded WebView/password capture.
3. On mobile, keep user-specific tokens in Android Keystore-backed encrypted storage, or in an audited backend scoped to an individual account (never in repositories/logs or unencrypted preferences). Rotate short-lived tokens, never request unnecessary permissions; revoke access on disconnect.
4. Back end: separate tenant identity and authorizations; no cross-player access; enforce authorization for every read; encrypted backups, redacted logs, rate limiting, monitoring and external security review.
5. Privacy: explicit per-source consent and data use; export/delete right, retention periods, privacy notice and GDPR review before launch.
6. Only activate GT7 field sync after field-level **source permission** is documented. PSN authentication alone does not imply access to GT7 race telemetry, opponents, race participation or driver-rating points.
7. No unofficial NPSSO cookie flow, no reverse-engineered mobile client keys, no unauthorized scraping for production. Those methods are unstable and can risk accounts.

## Source model
- `GT7_UDP`: first-party device telemetry received from user's own PS5, current car, laps, fuel, throttle, vehicle dynamics.
- `PITWALL_LOCAL`: recorded race sessions and Driver DNA, personal bests and pit decision quality.
- `GT7_CAREER_USER`: intentionally imported/supplied GT7 career profile stats, with timestamp.
- `PSN_OFFICIAL`: **future integration only**, gated behind written access approval.

## Safety boundary
No API wrapper is entitled to all GT7 profile fields just because it can log in to PSN. Everything that cannot be retrieved with an approved API remains 'unknown' and must not be reconstructed from guesses. Never use this module to automate PSN account actions or PS Store transactions.

## Business opportunity
The moat is the user's measured race behavior + race strategies + explainable recommendations. Public GT7 statistics are context, not the strategy engine. No resale or transfer of user credentials or raw play history without explicit agreement.
