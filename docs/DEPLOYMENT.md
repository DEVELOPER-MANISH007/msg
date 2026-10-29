# Free-tier setup boundaries

This document describes configuration only. No resources are deployed or activated by repository changes. Do not enable billing, a paid plan, paid overage, or purchase a domain.

1. Configure a Cloudflare Free Worker with SQLite-backed Durable Objects and a Supabase Free PostgreSQL project only if their current signup/setup flows do not require paid activation.
2. Apply `worker/schema.sql`. Row Level Security is enabled and direct `anon`/`authenticated` access is revoked; only Worker server-side authorization using its service-role secret should access the REST API.
3. Keep `SUPABASE_URL` and `SUPABASE_SERVICE_ROLE_KEY` in Worker secrets. Never set them in Android `BuildConfig`, resources, manifests, or client-readable configuration.
4. For local Worker development, use `worker/.dev.vars.example` as a placeholder template, then run `npm ci`, `npm test`, `npm run typecheck`, and `npm run dev`.
5. Pass the public Worker URL to Android builds with `-PchatApiBase=https://<worker>.workers.dev`. Release APK tasks reject a missing, non-HTTPS, or placeholder URL. Debug builds without a valid endpoint display a setup error and disable room creation/joining. Build/test commands do not deploy the Worker.

## Services and limitations

- Cloudflare Workers + SQLite Durable Objects: room authentication, short-lived room coordination, WebSocket signaling, and cleanup alarms. Free request/CPU/SQLite quotas are finite. The Worker has no content storage, no text/media relay, and no R2 binding.
- Supabase Free PostgreSQL: temporary room, participant, invitation, and cleanup-task metadata only. It may pause after inactivity, and database/egress quotas are finite. Unavailability must cause operations to fail; there is no paid fallback.
- Public STUN `stun:stun.l.google.com:19302`: best-effort ICE discovery only. It does not relay media. Its availability and data handling are external dependencies. Restrictive NAT/firewall networks can block direct connections.
- TURN: none configured. No hosted free TURN service with reliable availability, privacy, and quotas is promised. If direct ICE fails, the app should show a connection failure. Self-hosting coturn requires a public always-on host and bandwidth and cannot be guaranteed ₹0.
- Android WebRTC SDK, Android JCA, Compose, OkHttp, Gradle/Maven repositories: open-source/platform dependencies subject to upstream availability and version changes.
- R2, custom domain, analytics, paid media service, and crash reporting: not configured or used.

Cloudflare and Supabase limits and provider retention policies can change. Check the live official pricing and billing pages before any later deployment. Do not continue if a setup flow requires payment details or selects a metered paid service.
