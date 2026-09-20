# OpenCode Companion (Android)

A small, native Kotlin Android app that wraps the OpenCode web UI in a
WebView and adds a background foreground service that keeps a live OpenCode
event connection alive independently of the WebView, delivering native
Android notifications for things that actually need your attention
(permission requests, agent questions, session completion, session errors).
It also ships an **Android Auto** car app: browse sessions and projects,
have the last message read aloud, answer permission requests, and dictate
replies from the car screen.

## What this app is — and what it is not

**This app IS:**

- A thin native wrapper around the **real OpenCode web UI**, for people who
  like that UI and just want it on their phone.
- A **notification bridge**: a foreground service subscribes to your server's
  event stream and turns permission requests, agent questions, session
  completion, and errors into native Android notifications — even when the
  app is closed.
- An **Android Auto companion**: a driving-safe car-screen app (AndroidX Car
  App Library) with Sessions/Projects browsing, TTS readout of the last
  message, one-tap permission decisions, and dictated replies confirmed
  before sending.
- A deep-link launcher: tapping a notification opens the app straight into
  the relevant session in the WebView (verified web-UI route
  `/{base64url(directory)}/session/{id}`; the directory is learned from the
  global event envelope).

**This app IS NOT:**

- A reimplementation of the OpenCode UI — no native session/message/diff
  views on the phone, by design (the car app has its own minimal,
  driving-safe screens; that's a separate surface, not a phone UI).
- A client with its own conversation storage — nothing is cached or synced;
  no offline mode.
- A full-featured mobile OpenCode client.

**Looking for something more feature-rich?** If you want a full native
client experience (session management, message rendering, diffs, etc.),
take a look at projects like [OpenChamber](https://github.com/btriapitsyn/openchamber)
instead. This app intentionally stays minimal: it's for those who really
like the OpenCode web UI and only want native notification support on top
of it.

## Android Auto

The same APK includes a car-screen app (AndroidX Car App Library). On the
car launcher it appears as "OpenCode" and offers two starting points:

- **Sessions** — main sessions across all your projects (sub-agent and
  archived sessions are filtered out), running sessions marked and floated
  to the top, each row badged with the project's colored initial like the
  web UI.
- **Projects** — one row per project, drilling into that project's sessions.

A session detail shows the last message with **Read** (TTS read-aloud),
**Reply** (dictation with live partial text, read back for confirmation
before anything is sent), and a full-text view. Sessions with a pending
permission request instead show the command with **Allow / Deny / Allow
always** — the same endpoint as the notification actions.

Notes:
- Top-level navigation uses a tab bar on hosts with car API 8+ and falls
  back to a two-entry menu on older hosts.
- Release builds validate the car host against an allow-list (the official
  Android Auto app); AAOS system hosts pass via their privileged permission.
  Debug builds accept any host (needed for the Desktop Head Unit).
- Voice dictation requests the microphone permission at runtime, from the
  car screen, the first time you reply.

## Affiliation

This is an independent, community project. It is **not built by the
OpenCode team and is not affiliated with or endorsed by OpenCode in any
way**. "OpenCode" in the app name refers solely to the self-hosted server
it connects to.

## Project layout

```
app/src/main/java/it/ferdiu/opencodewrapper/
  api/            OpenCodeClient abstraction + the one real implementation
                  (OpenCodeClientV2) + tolerant event parsing (OcEvent)
  service/        Foreground service, reconnect/backoff policy, boot receiver
  notifications/  Channels + event -> notification decision logic
  data/           Encrypted config storage (server URL, auth header)
  ui/             MainActivity (WebView) + SettingsActivity
  auto/           Android Auto car app (Car App Library): car service/session,
                  tab/menu navigation, session readout + permission variant,
                  dictation + confirm screens, TTS speaker
```

The `api/` package is the isolation boundary called for in the design brief:
**everything else in the app talks to `OpenCodeClient`, never to raw
endpoint paths.** If/when OpenCode's V1→V2 migration changes the wire
protocol again, only `OpenCodeClientV2.kt` (or a new `OpenCodeClientV3`)
should need to change.

## What I verified against the actual v1.18.18 source, and what I had to assume

I inspected the `anomalyco/opencode` repository (the project moved from
`sst/opencode` to `anomalyco/opencode` on GitHub) at the `v1.18.18` tag —
including the SSE route handlers
(`server/routes/instance/httpapi/handlers/{event,global}.ts`) — and the
generated v2 SDK types (`packages/sdk/js/src/v2/gen/types.gen.ts`), rather
than assuming the protocol from memory or from older OpenCode versions.
Here's what's confirmed vs. what's a documented best guess:

**Confirmed:**
- `GET /global/health` — liveness/version check.
- `GET /global/event` — root-scoped SSE stream fed by the server's
  `GlobalBus`, forwarding events from **all** project instances. **This is
  the endpoint the app subscribes to.** The instance-scoped `GET /event`
  silently filters events server-side to a single instance directory
  (`event.location?.directory === instance.directory`, resolved from a
  `directory` query param), so without a directory the stream delivers only
  `server.connected`/heartbeats and no session events at all.
- Global-stream payloads arrive wrapped in an envelope
  (`{"directory": ..., "payload": {"id", "type", "properties"}}`), which
  `OcEventParser` unwraps.
- The event payloads themselves use the v2 schema:
  `permission.asked` → `properties = {id, sessionID, permission, patterns, ...}`;
  `question.asked` → `properties = {id, sessionID, questions: [{question, header, options, ...}]}`;
  `session.status` → `properties = {sessionID, status: {type: idle|busy|retry, ...}}`.
  Anything unrecognized becomes a harmless `Unknown` event instead of
  breaking the connection. **If a future server version changes this, the
  one file to fix is `api/OcEvent.kt`.**
- `GET /session`, `GET /session/status`, `GET /session/{id}` — session
  listing/status/detail, used for the app's minimal persisted-state needs
  and reconnect recovery.
- **Web-UI deep links**: session pages live at
  `/{base64url(directory)}/session/{id}` (URL-safe base64, no padding) per
  the web app router (`packages/app/src/app.tsx`,
  `utils/session-route.ts#legacySessionHref`).

**Explicitly *not* verified / best-effort, called out in code comments:**
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
- ~~Web app deep-link routing~~ — **now verified** (moved to "Confirmed"
  above): session pages are `/{base64url(directory)}/session/{id}`.

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

Standard Gradle Android project targeting `compileSdk 37`, `minSdk 26`
(foreground service types and notification channels both need API 26+).

Easiest path: open the project root in Android Studio and let it sync, then
Run. From the command line:

```
./gradlew assembleDebug
```

Then open Settings in the app and point it at your OpenCode server
(e.g. `http://192.168.1.10:4096` for a LAN server, or your public
HTTPS URL).
