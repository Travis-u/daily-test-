# PureMusic

Native Android MediaSession controller for NetEase Cloud Music. No WebView, web links,
audio download/decryption, account credentials or paid API.

## Discovery architecture

`MusicChatView` owns persisted user/select events, chat rendering and local preferences.
`RecommendationProvider` is the replacement boundary; `RecommendationEngine` is an
explicitly labelled offline stateful prototype with hard constraints, latest explicit
preference overrides, batch-scoped ordinals, selected song anchors and clarification.
`SongCatalog` contains real song identities and editorial descriptions. Unsupported
requests and exhausted catalogues return explanations, never fabricated music.

A future remote provider must use a backend (no API secret in APK), a verified music
catalogue resolver, structured song identities and cancellation/error handling. It must
not generate unverified NetEase IDs, treat model prose as a launch URI, or relax hard
constraints silently. Existing offline behaviour can remain an explicit fallback.

NetEase IDs/deep links have not been validated, so opening a card copies title+artist
and launches the explicit NetEase package. Search playback stays in PureMusic only
when the session advertises ACTION_PLAY_FROM_SEARCH; acceptance is not playback
confirmation, and the UI checks returned metadata after five seconds. No automatic
app launch on failure. Manual card open remains available.

Conversations stay in SharedPreferences; no network provider is wired. Clear removes
conversation and preference state. Replay preserves ordinal/refinement semantics across
activity recreation. The 100-event limit avoids silently dropping context.

## Validation

`bash tests/run.sh` with JDK 17 validates multi-turn constraints, rejection, references,
unsupported input, override, no-repeat/exhaustion, reset and deterministic replay.
CI runs this, `:app:assembleDebug` and `:app:lintDebug`, then publishes the APK against
that exact commit. Debug keystore is cached for future upgrades; this is not a
production signing setup.

Device checks still needed: install on API 24/30/36, grant notification access, start
NetEase playback, open chat, show keyboard, refine recommendations, rotate/relaunch,
use mini controls and return; test NetEase installed/missing, clipboard fallback,
search-play support/no support/ignored command, and confirm no browser launches.
