# Security and privacy threat model

## Architecture

The Android clients establish an authenticated signaling WebSocket with the Worker using ephemeral participant credentials. The Worker validates room membership and relays only bounded SDP/ICE signaling. Text, files, and call media use direct WebRTC transport; text/files are encrypted at the application layer with AES-GCM. The Worker rejects legacy text/key/file relay packet types. There is no R2 binding or server-side content queue.

Each room visit creates a fresh P-256 ECDH key pair. The peers derive a 256-bit key with HMAC-SHA-256 based derivation and use AES-GCM with a fresh random 96-bit nonce and room ID as associated data. Before sending, both users must compare the displayed key fingerprint over a separate trusted channel and confirm it in the app. The in-band confirmation proves both sides derived the same key; the out-of-band comparison is what detects an active signaling/peer substitution attack. Do not approve based only on matching a fingerprint displayed by the same potentially compromised connection.

The app does not use a ratchet protocol or claim Signal-level forward secrecy. A key exposed from memory can expose session content. Key clearing is best effort on the managed runtime. There has been no independent cryptographic audit.

## Transport and media

WebRTC uses public Google STUN `stun:stun.l.google.com:19302` and no TURN. Direct connectivity is not guaranteed across restrictive NAT/firewall networks; failure must be reported, without fallback to a paid relay. STUN is an external availability/privacy dependency and ICE candidates can reveal network details to the peer and STUN service. DataChannel traffic is peer-to-peer after establishment; WebRTC transport encryption and app-level AES-GCM provide separate protection layers. Audio/video uses WebRTC's encrypted media transport, but no in-app recording is implemented.

Files are capped at 10 MiB, transferred in 12 KiB encrypted chunks with bounded DataChannel backpressure, a single in-memory incoming assembly limit, cancellation, and SHA-256 integrity checking. This limits ordinary allocation but cannot prevent a malicious peer/device from exhausting resources through the underlying networking stack. Completed received files are held in process memory until explicitly exported or purged.

## Server and data risks

- Ephemeral bearer credentials authorize room operations; theft permits access until exit/expiry. Invitations are one-use and hashed in metadata.
- Worker signaling is authenticated and rate/size limited. It sees room/session metadata and SDP/ICE, not message plaintext or file contents.
- Supabase stores temporary room, participant, invitation, and cleanup metadata only. No plaintext, ciphertext history, file content, or encryption key is stored there. Service-role credentials must remain Worker-side and must not enter Android code/configuration.
- Provider-level operational logs/backups may retain request metadata under provider policies; this project does not verify or control their retention.
- Peer devices can copy, screenshot, export, record, or inspect decrypted content. A disconnected peer receives purge tombstones only when it reconnects; there is no remote erasure for content already copied outside the app.
- Android keyboard, accessibility, clipboard, screen capture, rooted-device malware, OS backups, and memory inspection may expose content.
- Supabase Free can pause inactive projects; provider quotas and outages can delay room creation or cleanup. No paid fallback is configured.

## Verification limits

Automated Worker and Android JVM tests do not demonstrate successful ICE negotiation, DataChannel delivery, camera/microphone behavior, Android lifecycle cleanup, or deletion across two actual devices. These require real-device testing on different network conditions. No live Cloudflare or Supabase project was configured here.
