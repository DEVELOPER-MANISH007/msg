# Ephemeral Chat

Anonymous two-person Android chat with direct WebRTC transport and a Cloudflare Worker used for room coordination and SDP/ICE signaling. Text and file bytes are sent over an encrypted WebRTC DataChannel; voice/video media uses WebRTC media tracks. Supabase PostgreSQL stores temporary room, participant, invitation, and cleanup metadata only. There is no server-side message queue, chat history, attachment store, analytics, or user account system.

## Implemented behavior

- Create a two-person room and join with a single-use invitation. The Worker authenticates each participant's ephemeral credential and rate-limits signaling.
- Establish direct WebRTC connectivity using a public STUN server. There is no TURN service, so some NAT/firewall combinations will fail clearly rather than relaying traffic through a paid service.
- Derive an ephemeral P-256 ECDH key and AES-GCM message/file key. Both participants must compare the displayed fingerprint out of band and confirm it before messaging, file transfer, or calls are enabled.
- Exchange text and encrypted files directly. Files are limited to 10 MiB and are sent in bounded chunks with backpressure, cancellation, progress, and SHA-256 integrity verification. Received files stay in memory until the user explicitly saves them.
- Start voice/video calls with microphone/camera permission prompts and in-call mute, camera, camera-switch, and hang-up controls.
- Exiting a participant sends a purge tombstone. Connected clients remove that participant's text and received files. A returning participant receives tombstones for peers who exited while it was offline. The final exit closes the room.

This is an implementation prototype, not an audited secure messenger. Android unit tests, Worker tests, and type checks pass, but no two-device Android session or live provider cleanup was verified in this environment. Do not infer real-world NAT traversal, call quality, or deletion from those automated results.

## ₹0 service constraints

No paid plan, billing, domain, or deployment is activated by this repository. Cloudflare Workers and SQLite-backed Durable Objects coordinate sessions; their request, CPU, storage, and operation free quotas are finite. Supabase Free is used for short-lived metadata and can pause after inactivity. Check provider terms and quotas before release; reaching a quota or requiring an unavailable service must fail with an error rather than trigger paid usage. R2 is not configured or used.

WebRTC uses `stun:stun.l.google.com:19302` as a best-effort external STUN dependency. It does not relay traffic and has no availability or privacy guarantee from this project. If direct ICE fails, there is no free hosted TURN alternative configured. A self-hosted coturn server requires an always-on public machine and bandwidth, so it cannot be promised reliably free. ICE candidates reveal network addressing information to the remote peer and STUN provider. No TURN bandwidth can be charged because none is configured.

The Android app uses the open-source `io.github.webrtc-sdk:android` package plus Android's JCA cryptography, Compose, and OkHttp. No R2, paid media service, analytics, or crash-reporting SDK is configured. Provider-side request/security logs and backups are outside this repository's deletion controls; see [retention](docs/RETENTION.md) and [threat model](docs/THREAT_MODEL.md).

## Build and local checks

1. Install JDK 17 and Android SDK 36, then open `android/` in Android Studio or run `cd android; .\gradlew.bat testDebugUnitTest assembleDebug`.
2. Run Worker checks with `cd worker; npm ci; npm test; npm run typecheck`.
3. Configure local Worker variables from `worker/.dev.vars.example`. Supabase service-role credentials belong only in Worker secrets; never add them to Android `BuildConfig`, resources, or client-accessible configuration. Apply `worker/schema.sql` to the Supabase project and retain its RLS policies.
4. Pass an existing HTTPS Worker URL to Android builds with `-PchatApiBase=https://<worker>.workers.dev`. Release packaging fails if the URL is absent or a placeholder; debug builds without one show an actionable setup error. No backend deployment is performed by the build commands.

On the Windows verification host, Gradle's JDK selector failed with the default `%TEMP%` path; a process-only short temp path fixed it. Example PowerShell setup (does not alter machine-wide settings):

```powershell
$toolRoot = Join-Path $env:LOCALAPPDATA 'EphemeralChatToolchain'
$env:JAVA_HOME = Join-Path $toolRoot 'jdk-17.0.20.1+1'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:ANDROID_HOME = Join-Path $toolRoot 'android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:TEMP = 'C:\t'; $env:TMP = 'C:\t'
cd android
.\gradlew.bat testDebugUnitTest assembleDebug
```

See [deployment constraints](docs/DEPLOYMENT.md) before connecting any provider. Do not deploy or activate billing without explicit approval.
