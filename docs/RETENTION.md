# Data retention and deletion

| Data | Location | Lifetime / deletion |
|---|---|---|
| Text bodies and file bytes | Android process memory and direct peer DataChannel | Never written to Worker, Durable Object SQLite, Supabase, or R2. The receiver keeps text and a completed file in app memory until sender purge, explicit leave, or process termination. The user may explicitly export a received file, which is then outside app-controlled deletion. |
| Call audio/video | Direct WebRTC peer connection | Streamed between devices; not recorded or stored by this app. Device/OS capture behavior is outside the app. |
| Room, invitation, participant metadata | Durable Object SQLite and Supabase PostgreSQL | Room lease defaults to 6 hours. Invitation token is hashed and single-use. Participant status/activity/exit are temporary. The room is deleted after final exit or expiry, subject to provider availability. |
| Cleanup task metadata | Durable Object SQLite and Supabase | Retried with backoff while needed. Successful records are removed. The application does not deliberately retain completed tasks; provider-level logs/backups are outside its control. |
| Ephemeral credentials | Android process memory; credential hash in Durable Object SQLite | Removed with room state after final exit/expiry. |
| Cryptographic private/shared keys | Android process memory | Best-effort byte clearing and reference disposal on exit/process termination. Keys are not written to preferences, files, Room, or PostgreSQL. |
| Local chat history | None | No persistent chat database or message history is implemented. |

Participant exit authenticates the ephemeral credential, marks the member exited, closes its signaling socket, and broadcasts a `purge-sender` tombstone. A connected client removes that sender's displayed text, completed file bytes, and in-progress file buffers. On reconnect, the Worker resends tombstones for exited room members; no content replay occurs. The other participant's items remain. When the final active participant exits, the room closes and room metadata cleanup is requested. Duplicate exit is rejected as unauthorized after the first successful exit; cleanup is designed to be idempotent and retried.

Abandoned room/participant cleanup is alarm-driven. The Worker cannot guarantee timely cleanup if Cloudflare or Supabase is unavailable, paused, or has exhausted free quotas. Completed cleanup records are removed after successful work; failures retain a pending task for retry. Live provider behavior has not been tested here.

WebRTC direct connections do not store media on the service. The public STUN service receives ICE requests and the remote peer can learn ICE candidate network details. No TURN relay is configured. R2 is not configured. The app does not promise deletion of content after a recipient exports, screenshots, copies, records, or backs it up.

Cloudflare and Supabase may retain provider-level operational/security logs or backups according to their policies. This repository does not verify provider retention or promise deletion from logs/backups. It does not intentionally log chat content or keys, and it has no analytics or crash-reporting SDK. No live service was configured in this verification.
