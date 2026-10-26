# GridStats public profile (experimental)

Read-only opt-in access to https://gt-gridstats.com/player/{PSN_ID} .
Sony's statistics API requires a Sony login token and the documented
GridStats JSON API requires a developer bearer token. Pitwall requests neither.

The page visibly publishes DR/SR for indexed players. Its Total Entries,
Victories and Poles fields may be zero even with other recorded race history.
Those counters MUST NOT be imported as official career totals.

Pilot opts in explicitly. Automatic refresh interval: 6h minimum.
Fixed HTTPS destination, validated PSN identifier, no redirects followed,
HTML size 1.5MB limit, no bypass for 403/captcha, no background crawler.
Only rank letters with a matching profile ID are imported and source-labeled.
A failure preserves existing stats; no missing values are fabricated.
Source owners can change availability or access policy without notice.

No live device or external raw HTML test was possible in this session.
Before release, validate on a physical Galaxy Fold using a real public
profile and check app builds in CI. Prefer a permitted GridStats API
integration with a safely stored user-owned key for complete career stats.
