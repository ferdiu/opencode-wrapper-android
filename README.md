# OpenCode Companion (Android)

A small, native Kotlin Android app that wraps the OpenCode web UI in a
WebView and adds a background foreground service that keeps a live OpenCode
event connection alive independently of the WebView, delivering native
Android notifications for things that actually need your attention
(permission requests, agent questions, session completion, session errors).

It deliberately does **not** reimplement the OpenCode UI, cache
conversations, or work offline. The web app remains the primary UI; this app
just makes sure you don't miss things while it's backgrounded.

## Project layout

```
app/src/main/java/it/ferdiu/opencodewrapper/
  api/            OpenCodeClient abstraction + the one real implementation
                  (OpenCodeClientV2) + tolerant event parsing (OcEvent)
  service/        Foreground service, reconnect/backoff policy, boot receiver
  notifications/  Channels + event -> notification decision logic
  data/           Encrypted config storage (server URL, auth header)
  ui/             MainActivity (WebView) + SettingsActivity
```

The `api/` package is the isolation boundary called for in the design brief:
**everything else in the app talks to `OpenCodeClient`, never to raw
endpoint paths.** If/when OpenCode's V1→V2 migration changes the wire
protocol again, only `OpenCodeClientV2.kt` (or a new `OpenCodeClientV3`)
should need to change.

## What I verified against the actual v1.18.18 source, and what I had to assume

I inspected the `anomalyco/opencode` repository (the project moved from
`sst/opencode` to `anomalyco/opencode` on GitHub) at the `v1.18.18` tag,
its generated SDK types (`packages/sdk/js/src/gen/types.gen.ts`), and
documentation of the v2 SDK/OpenAPI surface, rather than assuming the
protocol from memory or from older OpenCode versions. Here's what's
confirmed vs. what's a documented best guess:

**Confirmed:**
- `GET /global/health` — liveness/version check.
- `GET /event` — instance-scoped SSE stream (this is the endpoint to use;
  `GET /global/event` is the legacy, cross-instance endpoint and is
  deliberately *not* used here).
- Adding `?session={sessionID}` to `GET /event` filters the stream to that
  session's events plus connection-level `server.*` events. This filter was
  added specifically for `@opencode-ai/sdk/v2` (upstream PR #6729 says
  "`@opencode-ai/sdk/v2` — Fully supported"). **This is what "the V2 event
  API" concretely means in v1.18.18** — there isn't a separate `/v2/event`
  URL. `OpenCodeClientV2.events()` exposes this via an optional `sessionId`
  parameter, though the service currently subscribes unfiltered
  (`sessionId = null`) so it catches events across every session on the
  instance.
- `GET /session`, `GET /session/status`, `GET /session/{id}` — session
  listing/status/detail, used for the app's minimal persisted-state needs
  and reconnect recovery.
- The SSE payload shape: `{"type": "...", "properties": {...}}`, with a
  `server.connected` event sent immediately on connect and periodic
  heartbeats.

**Explicitly *not* verified / best-effort, called out in code comments:**
- **Event naming for permission/question events.** The v1 SDK types use
  `permission.updated` / `permission.replied`. Secondary documentation of
  the v2 unified `Event` type also references `permission.asked` and
  `question.asked` as separate members, but I wasn't able to fetch the full
  v2 `types.gen.ts` to confirm the exact shape. `OcEventParser` recognizes
  **both** naming schemes for permission events and handles `question.asked`
  if present; anything it doesn't recognize becomes a harmless `Unknown`
  event instead of breaking the connection. **If you're on a server where
  this doesn't match, this is the one file to fix:
  `api/OcEvent.kt`.**
- **Durable replay/cursor endpoint.** I could not find a documented,
  stable "durable event log with a replay cursor" distinct from session
  status/detail. So reconnect recovery here is: reconnect the SSE stream,
  then re-fetch `/session/status` to detect any idle/busy/error transition
  that happened while disconnected — not true event-log replay. If a future
  OpenCode release adds an explicit cursor/replay endpoint, only
  `OpenCodeClient.getSessionSnapshot`/`listActiveSessions` and the recovery
  pass in `OpenCodeEventService` need to change.
- **Server-level auth.** OpenCode's HTTP server doesn't document a built-in
  bearer-auth scheme for v1.18.18 — the `/auth/{providerID}` endpoints
  configure *LLM provider* credentials, not access to the OpenCode server
  itself. Server access control is normally handled by whatever reverse
  proxy sits in front of `opencode serve`. So the app just attaches
  whatever raw header value you type into Settings (accepts either
  `HeaderName: value` or a bare value, which defaults to `Authorization`)
  on every request, rather than assuming one specific scheme.
- **Web app deep-link routing.** Tapping a notification loads
  `{baseUrl}/session/{id}` in the WebView. This matches the confirmed *API*
  path and is a reasonable guess for the web app's client-side router, but
  I couldn't verify the actual SPA routing scheme. If your deployment uses
  something else (a hash route, a query param, etc.), the one place to
  change is `MainActivity.sessionUrl()`.

## Background execution notes

- The service is declared with `foregroundServiceType="specialUse"`, with
  the required `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` justification string in
  the manifest. `dataSync` was considered but rejected: this is an
  indefinite, always-on connection, not a bounded sync task, and Android
  restricts `dataSync` foreground services to bounded runtimes (with OS
  enforcement in newer versions) which doesn't fit here.
- Reconnects use exponential backoff with jitter (`ReconnectPolicy`),
  capped at 60s, so a server restart doesn't get hammered by every client
  reconnecting in lockstep.
- The app asks the user to exempt it from battery optimization (a system
  dialog, not silently granted) since Doze can otherwise suspend the
  service's network access.
- `BootReceiver` restarts the service after a reboot if a server is already
  configured, so notifications keep working without having to reopen the
  app.

## Explicitly out of scope (per the design brief)

- No local database or cached copy of conversation content — only the
  server URL, optional auth header, and last-opened session id are
  persisted (in `EncryptedSharedPreferences`).
- No custom/native UI for sessions, messages, or diffs — that's what the
  WebView + the real OpenCode web app are for.
- No offline mode.

## Building

Standard Gradle Android project targeting `compileSdk 35`, `minSdk 26`
(foreground service types and notification channels both need API 26+).

Easiest path: open the project root in Android Studio (Koala or newer) and
let it sync/generate the Gradle wrapper automatically, then Run.

Command line: the wrapper jar/scripts aren't included in this archive
(binary, and not generatable without network access here) — generate them
once with a local Gradle install, then use `./gradlew` as normal:

```
gradle wrapper --gradle-version 8.9
./gradlew assembleDebug
```

Then open Settings in the app and point it at your OpenCode server
(e.g. `http://192.168.1.10:4096` for a LAN server, or your public
HTTPS URL).
