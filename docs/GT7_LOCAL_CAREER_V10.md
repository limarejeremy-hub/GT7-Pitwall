# V10 — Career facts without a PSN API key

**Purpose:** make our own PS5-recorded history useful immediately; keep it separate from the driver's historic GT7 online career.

## Two independent sources

| Source | What we can prove | When it updates |
|---|---|---|
| **GT7 UDP → Pitwall local archives** | Session timestamp, laps, car code, circuit when identified, fuel, driver performance, finish/grid position **when observed**, coaching events | Locally when a lap ends, an event completes, or the app closes a race |
| **GT7 profile and GT GridStats** | All-time Sport races, DR/SR, points, victories, etc. | Provider's remote records; NOT present in UDP and NOT automatically available by PSN handle |

## V10 delivered
- `PitwallLocalCareer.fromSessions()` rebuilds the user's recorded career at startup from **ALL** sessions, not only sessions comparable to the currently selected Sport A/B/C.
- Sessions saved repeatedly during racing are deduplicated by session ID; latest archive wins.
- Only confirmed *Sport official / race / finished* sessions count as **Sport races**.
- Victory, podium, Top 5 and gained positions require real finish/grid position evidence; missing positions are not converted into false zeros.
- On a fresh app start **without PS5 connection**, existing sessions still appear in Briefing and Atelier.
- UI refreshes after stops and as new GT7 laps are saved; local data do not require a PSN profile, consent to any third-party service or an account password.
- RaceStore writes use Android `AtomicFile` to preserve previous session archive on interrupted writes; failures are logged rather than silently ignored.
- No existing session/profile file is migrated, overwritten during install or deleted.
- Tests cover incomplete/unknown mode, duplicated archive snapshots, missing positions, best time and coverage.

## What V10 cannot claim
- It cannot reconstruct 71 historic Sport races from pre-Pitwall sessions.
- A standalone PSN ID cannot authenticate to private Gran Turismo data; there is no automatic connection to Sony's GT7 stats endpoints here.
- GT GridStats offers `GET /api/racers/{identifiers}` as a **metered token-protected API**. Any commercial use requires a granted API credential and permission; embedding one shared service key directly in a public Android APK is not secure.
- Gran Turismo's own profile services documented in community research require the user's authorized **Sony session bearer**. Pitwall must not collect a user's session cookie/NPSSO or impersonate a Sony client to bypass permissions.

## Follow-on integration (only after access approval)
- Build a provider interface `CareerSource.fetch(psnId)` behind a secure relay under our control, using **licensed/authorized** GT7 player-profile sources.
- Store provenance/last sync and resolve fields independently (do not overwrite older points with null).
- Backoff/cache to respect rate limits; tokens stay backend-side and are never distributed to phone clients.
- Do not merge public career totals with local Pitwall counts: display both and their separate coverage windows.

## Validation on Fold
Start V10 with PS5 off: Briefing must already show archived sessions and completed timed laps. Play one verified Sport race in GT7: after the finish, only that measured race is added. Without confirmed grid/finish position, the result remains marked unknown. Confirm the 71 global races/4 victories from a previously imported profile still appear separately under “profil GT7 en ligne”.
