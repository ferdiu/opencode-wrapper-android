# OpenCodeWrapper

Android companion app for a self-hosted [OpenCode](https://opencode.ai) server.
Keeps a background foreground-service connection (SSE) alive so agent
notifications (permission requests, questions, session completion) reach the
device even when the app is closed.

## Tech Stack

- Kotlin, Android Gradle Plugin (Gradle 9.x), minSdk 26, targetSdk 35
- ViewBinding (no Compose), AppCompat + Material Components
- OkHttp (+ okhttp-sse) for REST and the SSE event stream
- kotlinx-serialization-json for API payloads
- WorkManager as backup for reconnect nudges
- androidx.security-crypto for storing the auth token

## Project Layout

- `app/src/main/java/it/ferdiu/opencodewrapper/` — all Kotlin sources
  - `api/` — OpenCode REST/SSE clients and event models
  - `data/` — ServerConfigStore (persisted server URL + auth token)
  - `notifications/` — channels and routing of events to notifications
  - `service/` — OpenCodeEventService (foreground service), BootReceiver, ReconnectPolicy
  - `ui/` — MainActivity (connection/web view), SettingsActivity
- `app/src/main/res/` — layouts, drawables, values, launcher icon

## Build & Test

- Build: `export JAVA_HOME=/usr/lib/jvm/temurin-25-jdk; ./gradlew assembleDebug`
- Full check: `export JAVA_HOME=/usr/lib/jvm/temurin-25-jdk; ./gradlew build`
- Release is minified (ProGuard); keep keep-rules in `app/proguard-rules.pro` when adding reflection/serialization.

## Conventions

- Package: `it.ferdiu.opencodewrapper` (applicationId matches).
- App display name: "OpenCode".
- App icon is derived from `opencode.svg` (repo root) — foreground vector + `#131010` background.
- `usesCleartextTraffic="true"` is intentional (self-hosted http:// LAN/Tailscale
  servers). Do not "fix" it silently; tighten only via network_security_config if needed.
- No compilation warnings are tolerated: after any change, build and clean up new warnings.
- Conventional commits (`feat:`, `fix:`, `chore:`, ...).

## Notes for Agents

- Reference files with relative paths from the project root.
- Execution may be sandboxed: if a command fails in a way that looks
  sandbox-related (not code-related), print the exact command and ask the user
  to run it and share the output.
- NEVER commit anything under `docs/superpowers`.
