export interface Env {
  ROOMS: DurableObjectNamespace;
  SUPABASE_URL: string;
  SUPABASE_SERVICE_ROLE_KEY: string;
  ROOM_TTL_SECONDS: string;
  MAX_SIGNALING_PER_MINUTE: string;
}

const json = (data: unknown, status = 200) => Response.json(data, { status, headers: { "Cache-Control": "no-store" } });
const fail = (message: string, status = 400) => json({ error: message }, status);
const random = (n = 24) => { const b = crypto.getRandomValues(new Uint8Array(n)); return btoa(String.fromCharCode(...b)).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", ""); };
const hex = (b: ArrayBuffer) => [...new Uint8Array(b)].map(x => x.toString(16).padStart(2, "0")).join("");
const hash = async (value: string) => hex(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value)));
const auth = (request: Request) => request.headers.get("authorization")?.match(/^Bearer ([A-Za-z0-9_.-]{24,})$/)?.[1] ?? null;
const supabase = async (env: Env, table: string, method: string, body?: unknown, query = "") => {
  const res = await fetch(`${env.SUPABASE_URL}/rest/v1/${table}${query}`, {
    method, headers: { apikey: env.SUPABASE_SERVICE_ROLE_KEY, Authorization: `Bearer ${env.SUPABASE_SERVICE_ROLE_KEY}`, "Content-Type": "application/json", Prefer: "return=minimal" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`metadata provider returned ${res.status}`);
};
const roomStub = (env: Env, id: string) => env.ROOMS.get(env.ROOMS.idFromName(id));

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url); const parts = url.pathname.split("/").filter(Boolean);
    if (request.method === "GET" && url.pathname === "/health") return json({ ok: true });
    if (request.method === "POST" && url.pathname === "/v1/rooms") {
      const id = random(18), participantId = random(12), credential = random(32), invite = random(24);
      const now = Date.now(), expiry = now + Math.min(86400, Math.max(900, Number(env.ROOM_TTL_SECONDS) || 21600)) * 1000;
      const tokenHash = await hash(invite);
      try {
        await supabase(env, "rooms", "POST", { id, status: "waiting", created_at: new Date(now).toISOString(), expires_at: new Date(expiry).toISOString(), max_participants: 2 });
        await supabase(env, "participants", "POST", { id: participantId, room_id: id, status: "active", joined_at: new Date(now).toISOString(), last_activity_at: new Date(now).toISOString() });
        await supabase(env, "invitations", "POST", { token_hash: tokenHash, room_id: id, expires_at: new Date(expiry).toISOString() });
        const created = await roomStub(env, id).fetch("https://room.internal/create", { method: "POST", body: JSON.stringify({ id, expiry, participants: [{ id, credentialHash: await hash(credential) }], inviteHash: tokenHash }) });
        if (!created.ok) throw new Error("room initialization failed");
      } catch { return fail("Room service unavailable. Try again later.", 503); }
      return json({ roomId: id, participantId, credential, invitation: `${url.origin}/join/${id}#${invite}`, expiresAt: expiry });
    }
    if (request.method === "POST" && parts.length === 3 && parts[0] === "v1" && parts[1] === "rooms" && parts[2] === "join") {
      const id = parts[2];
      return fail("Use POST /v1/rooms/{roomId}/join", 404);
    }
    if (request.method === "POST" && parts.length === 4 && parts[0] === "v1" && parts[1] === "rooms" && parts[3] === "join") {
      const id = parts[2]; let body: any;
      try { body = await request.json(); } catch { return fail("Invalid JSON"); }
      if (typeof body.invitation !== "string") return fail("Invitation is required");
      const participantId = random(12), credential = random(32), tokenHash = await hash(body.invitation);
      if (body.invitation.length < 24 || body.invitation.length > 100) return fail("Invalid invitation");
      let accepted = false;
      try {
        const joined = await roomStub(env, id).fetch("https://room.internal/join", { method: "POST", body: JSON.stringify({ invitationHash: tokenHash, participantId, credentialHash: await hash(credential) }) });
        if (!joined.ok) return fail("Invitation is invalid, expired, or room is full.", 403);
        accepted = true;
        await supabase(env, "invitations", "PATCH", { used: true }, `?token_hash=eq.${tokenHash}&used=eq.false&revoked=eq.false`);
        await supabase(env, "participants", "POST", { id: participantId, room_id: id, status: "active", joined_at: new Date().toISOString(), last_activity_at: new Date().toISOString() });
        await supabase(env, "rooms", "PATCH", { status: "active" }, `?id=eq.${id}`);
      } catch {
        if (accepted) await roomStub(env, id).fetch("https://room.internal/join-rollback", { method: "POST", body: JSON.stringify({ participantId }) });
        try { await supabase(env, "participants", "DELETE", undefined, `?id=eq.${participantId}`); await supabase(env, "invitations", "PATCH", { used: false }, `?token_hash=eq.${tokenHash}`); } catch { }
        return fail("Room service unavailable. Try again later.", 503);
      }
      return json({ roomId: id, participantId, credential });
    }
    if (parts[0] === "v1" && parts[1] === "rooms" && parts[2] && parts[3] === "socket" && request.headers.get("Upgrade")?.toLowerCase() === "websocket") {
      const credential = auth(request); if (!credential) return fail("Authentication required", 401);
      return roomStub(env, parts[2]).fetch(new Request("https://room.internal/socket", request));
    }
    if (parts[0] === "v1" && parts[1] === "rooms" && parts[2] && parts[3] === "exit" && request.method === "POST") {
      if (!auth(request)) return fail("Authentication required", 401);
      return roomStub(env, parts[2]).fetch(new Request("https://room.internal/exit", { method: "POST", headers: { Authorization: request.headers.get("Authorization")! } }));
    }
    return fail("Not found", 404);
  },
  async scheduled(_event: ScheduledController, env: Env): Promise<void> {
    try { await fetch(`${env.SUPABASE_URL}/rest/v1/rpc/prune_ephemeral_metadata`, { method: "POST", headers: { apikey: env.SUPABASE_SERVICE_ROLE_KEY, Authorization: `Bearer ${env.SUPABASE_SERVICE_ROLE_KEY}` } }); } catch { /* cleanup retries next schedule */ }
  },
};

import { DurableObject } from "cloudflare:workers";

export class Room extends DurableObject<Env> {
  private sockets = new Map<WebSocket, string>();
  private limits = new Map<string, number[]>();
  constructor(state: DurableObjectState, env: Env) {
    super(state, env);
    this.state = state;
    state.blockConcurrencyWhile(async () => {
      state.storage.sql.exec(`CREATE TABLE IF NOT EXISTS room_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS members (id TEXT PRIMARY KEY, credential_hash TEXT NOT NULL, status TEXT NOT NULL, last_active INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS cleanup_tasks (id TEXT PRIMARY KEY, object_key TEXT NOT NULL, retries INTEGER NOT NULL, next_at INTEGER NOT NULL);`);
      const columns = [...state.storage.sql.exec<{ name: string }>("PRAGMA table_info(members)")].map(row => row.name);
      if (columns.includes("public_key")) { state.storage.sql.exec("UPDATE members SET public_key=NULL"); state.storage.sql.exec("ALTER TABLE members DROP COLUMN public_key"); }
      state.storage.sql.exec("DROP TABLE IF EXISTS files");
      for (const socket of state.getWebSockets()) {
        const attachment = socket.deserializeAttachment() as { participant?: string } | null;
        if (attachment?.participant) this.sockets.set(socket, attachment.participant);
      }
    });
  }
  private state: DurableObjectState;
  private async digest(value: string) { return hex(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value))); }
  private async member(request: Request) {
    const token = auth(request); if (!token) return null;
    const credentialHash = await this.digest(token);
    const found = [...this.state.storage.sql.exec<{ id: string; status: string }>("SELECT id,status FROM members WHERE credential_hash=?", credentialHash)];
    return found[0]?.status === "active" ? found[0].id : null;
  }
  private async touch(participantId: string, now = Date.now()) {
    const previous = [...this.state.storage.sql.exec<{ last_active: number }>("SELECT last_active FROM members WHERE id=? AND status='active'", participantId)][0];
    if (!previous) return;
    this.state.storage.sql.exec("UPDATE members SET last_active=? WHERE id=?", now, participantId);
    if (now - previous.last_active >= 60000) {
      try { await supabase(this.env, "participants", "PATCH", { last_activity_at: new Date(now).toISOString() }, `?id=eq.${participantId}&status=eq.active`); } catch { }
    }
  }
  private ensureSchema() {
    this.state.storage.sql.exec(`CREATE TABLE IF NOT EXISTS room_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS members (id TEXT PRIMARY KEY, credential_hash TEXT NOT NULL, status TEXT NOT NULL, last_active INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS cleanup_tasks (id TEXT PRIMARY KEY, object_key TEXT NOT NULL, retries INTEGER NOT NULL, next_at INTEGER NOT NULL);`);
    const columns = [...this.state.storage.sql.exec<{ name: string }>("PRAGMA table_info(members)")].map(row => row.name);
    if (columns.includes("public_key")) { this.state.storage.sql.exec("UPDATE members SET public_key=NULL"); this.state.storage.sql.exec("ALTER TABLE members DROP COLUMN public_key"); }
    this.state.storage.sql.exec("DROP TABLE IF EXISTS files");
  }
  async fetch(request: Request): Promise<Response> {
    this.ensureSchema();
    const path = new URL(request.url).pathname;
    if (path === "/create" && request.method === "POST") {
      const body: any = await request.json();
      if (this.meta("expiry")) return fail("Already initialized", 409);
      this.state.storage.sql.exec("INSERT INTO room_meta(key,value) VALUES('expiry',?),('inviteHash',?),('closed','false')", String(body.expiry), body.inviteHash);
      this.state.storage.sql.exec("INSERT INTO room_meta(key,value) VALUES('roomId',?)", body.id);
      for (const p of body.participants) this.state.storage.sql.exec("INSERT INTO members(id,credential_hash,status,last_active) VALUES(?,?,?,?)", p.id, p.credentialHash, "active", Date.now());
      await this.state.storage.setAlarm(Math.min(body.expiry, Date.now() + 300000)); return json({ ok: true });
    }
    if (path === "/join" && request.method === "POST") {
      const body: any = await request.json();
      if (this.meta("closed") !== "false" || Number(this.meta("expiry")) < Date.now() || this.meta("inviteHash") !== body.invitationHash || this.meta("inviteUsed") === "true") return fail("Invalid invitation", 403);
      if ([...this.state.storage.sql.exec("SELECT id FROM members WHERE status='active'")].length >= 2) return fail("Room full", 403);
      this.state.storage.sql.exec("INSERT INTO members(id,credential_hash,status,last_active) VALUES(?,?,?,?)", body.participantId, body.credentialHash, "active", Date.now());
      this.state.storage.sql.exec("UPDATE room_meta SET value='true' WHERE key='inviteUsed'");
      this.state.storage.sql.exec("INSERT OR REPLACE INTO room_meta(key,value) VALUES('inviteUsed','true')");
      return json({ ok: true });
    }
    if (path === "/join-rollback" && request.method === "POST") {
      const body: any = await request.json();
      this.state.storage.sql.exec("DELETE FROM members WHERE id=? AND status='active'", body.participantId);
      this.state.storage.sql.exec("DELETE FROM room_meta WHERE key='inviteUsed'");
      return json({ ok: true });
    }
    if (path === "/socket" && request.headers.get("Upgrade")?.toLowerCase() === "websocket") {
      const participant = await this.member(request); if (!participant) return fail("Unauthorized", 401);
      const pair = new WebSocketPair(); const [client, server] = Object.values(pair);
      this.state.acceptWebSocket(server); this.sockets.set(server, participant);
      server.serializeAttachment({ participant });
      await this.touch(participant);
      const activePeers = [...this.state.storage.sql.exec<{ id: string }>("SELECT id FROM members WHERE status='active' AND id<>?", participant)];
      for (const peer of activePeers) {
        server.send(JSON.stringify({ type: "peer-ready", participantId: peer.id }));
        this.broadcast({ type: "peer-ready", participantId: participant }, participant);
      }
      // An exited peer's in-memory messages can be purged after reconnect. There is
      // no message replay or delivery queue to restore any chat content.
      for (const exited of this.state.storage.sql.exec<{ id: string }>("SELECT id FROM members WHERE status='exited'")) server.send(JSON.stringify({ type: "purge-sender", senderId: exited.id }));
      return new Response(null, { status: 101, webSocket: client });
    }
    if (path === "/exit" && request.method === "POST") {
      const participant = await this.member(request); if (!participant) return fail("Unauthorized or already exited", 401);
      await this.exit(participant); return json({ ok: true });
    }
    return fail("Not found", 404);
  }
  private meta(key: string) { return [...this.state.storage.sql.exec<{ value: string }>("SELECT value FROM room_meta WHERE key=?", key)][0]?.value; }
  async webSocketMessage(socket: WebSocket, message: string | ArrayBuffer) {
    const sender = this.sockets.get(socket); if (!sender || typeof message !== "string" || message.length > 70000) { socket.close(1008, "Invalid signaling packet"); return; }
    const now = Date.now(), recent = (this.limits.get(sender) || []).filter(t => now - t < 60000);
    if (recent.length >= (Number(this.env.MAX_SIGNALING_PER_MINUTE) || 60)) { socket.send(JSON.stringify({ type: "error", code: "rate_limited" })); return; }
    recent.push(now); this.limits.set(sender, recent);
    let packet: any; try { packet = JSON.parse(message); } catch { socket.close(1003, "Malformed packet"); return; }
    if (["text", "key-exchange", "attachment", "file-start", "file-chunk", "file-end", "file-cancel"].includes(packet.type)) { socket.send(JSON.stringify({ type: "error", code: "direct-channel-required" })); return; }
    let signal: Record<string, unknown> | null = null;
    if (["offer", "answer", "call-offer", "call-answer"].includes(packet.type) && typeof packet.sdp === "string" && packet.sdp.length <= 65000) signal = { type: packet.type, sdp: packet.sdp };
    else if (packet.type === "ice" && typeof packet.candidate === "string" && packet.candidate.length <= 4096 && (packet.sdpMid == null || (typeof packet.sdpMid === "string" && packet.sdpMid.length <= 128)) && Number.isInteger(packet.sdpMLineIndex) && packet.sdpMLineIndex >= 0 && packet.sdpMLineIndex <= 32) signal = { type: "ice", candidate: packet.candidate, sdpMid: packet.sdpMid ?? null, sdpMLineIndex: packet.sdpMLineIndex };
    else if (packet.type === "call-end") signal = { type: "call-end" };
    if (signal) { await this.touch(sender, now); this.broadcast({ ...signal, senderId: sender }, sender); return; }
    socket.send(JSON.stringify({ type: "error", code: "invalid_packet" }));
  }
  webSocketClose(socket: WebSocket) { this.sockets.delete(socket); }
  webSocketError(socket: WebSocket) { this.sockets.delete(socket); }
  private broadcast(message: unknown, exclude?: string) {
    const raw = JSON.stringify(message);
    for (const [socket, id] of this.sockets) if (id !== exclude && socket.readyState === WebSocket.OPEN) socket.send(raw);
  }
  private async enqueueCleanup(id: string, objectKey: string, sessionId: string) {
    const next = Date.now() + 60000;
    this.state.storage.sql.exec("INSERT INTO cleanup_tasks(id,object_key,retries,next_at) VALUES(?,?,0,?) ON CONFLICT(id) DO UPDATE SET next_at=excluded.next_at", id, objectKey, next);
    try { await supabase(this.env, "cleanup_tasks", "POST", { id, session_id: sessionId, retry_count: 0, status: "pending", next_retry_at: new Date(next).toISOString() }); } catch { }
    await this.state.storage.setAlarm(next);
  }
  private async exit(id: string) {
    const member = [...this.state.storage.sql.exec<{ status: string }>("SELECT status FROM members WHERE id=?", id)][0];
    if (!member || member.status !== "active") return;
    this.state.storage.sql.exec("UPDATE members SET status='exited' WHERE id=?", id);
    this.broadcast({ type: "purge-sender", senderId: id });
    for (const [socket, participant] of this.sockets) if (participant === id) { socket.close(1000, "Exited"); this.sockets.delete(socket); }
    try { await supabase(this.env, "participants", "PATCH", { status: "exited", exited_at: new Date().toISOString() }, `?id=eq.${id}`); }
    catch { await this.enqueueCleanup(`meta-participant:${id}`, `meta-participant:${id}`, id); }
    const count = [...this.state.storage.sql.exec("SELECT id FROM members WHERE status='active'")].length;
    if (count === 0) await this.closeRoom();
  }
  private async closeRoom() {
    this.state.storage.sql.exec("UPDATE room_meta SET value='true' WHERE key='closed'");
    this.broadcast({ type: "room-closed" });
    try { await supabase(this.env, "rooms", "DELETE", undefined, `?id=eq.${this.meta("roomId")}`); }
    catch { await this.enqueueCleanup(`meta-room:${this.meta("roomId")}`, `meta-room:${this.meta("roomId")}`, this.meta("roomId") || ""); }
    for (const s of this.state.getWebSockets()) s.close(1000, "Room closed");
    if ([...this.state.storage.sql.exec("SELECT id FROM cleanup_tasks")].length) await this.state.storage.setAlarm(Date.now() + 60000);
    else await this.state.storage.deleteAll();
  }
  async alarm() {
    const tasks = [...this.state.storage.sql.exec<{ id: string; object_key: string; retries: number }>("SELECT id,object_key,retries FROM cleanup_tasks WHERE next_at<=?", Date.now())];
    for (const task of tasks) {
      try {
        if (task.object_key.startsWith("meta-participant:")) await supabase(this.env, "participants", "PATCH", { status: "exited", exited_at: new Date().toISOString() }, `?id=eq.${task.object_key.slice("meta-participant:".length)}`);
        else if (task.object_key.startsWith("meta-room:")) await supabase(this.env, "rooms", "DELETE", undefined, `?id=eq.${task.object_key.slice("meta-room:".length)}`);
        else throw new Error("Unknown cleanup task");
        this.state.storage.sql.exec("DELETE FROM cleanup_tasks WHERE id=?", task.id);
        try { await supabase(this.env, "cleanup_tasks", "DELETE", undefined, `?id=eq.${task.id}`); } catch { }
      } catch {
        const next = Date.now() + Math.min(3600000, 60000 * 2 ** Math.min(task.retries, 5));
        this.state.storage.sql.exec("UPDATE cleanup_tasks SET retries=retries+1,next_at=? WHERE id=?", next, task.id);
        try { await supabase(this.env, "cleanup_tasks", "PATCH", { retry_count: task.retries + 1, next_retry_at: new Date(next).toISOString() }, `?id=eq.${task.id}`); } catch { }
      }
    }
    if (this.meta("closed") === "true") {
      if ([...this.state.storage.sql.exec("SELECT id FROM cleanup_tasks")].length) await this.state.storage.setAlarm(Date.now() + 300000);
      else await this.state.storage.deleteAll();
      return;
    }
    const expiry = Number(this.meta("expiry"));
    if (!Number.isFinite(expiry) || expiry <= Date.now()) { await this.closeRoom(); return; }
    const connected = new Set([...this.sockets.values()]);
    const stale = [...this.state.storage.sql.exec<{ id: string }>("SELECT id FROM members WHERE status='active' AND last_active<?", Date.now() - 900000)].filter(p => !connected.has(p.id));
    for (const p of stale) await this.exit(p.id);
    if (this.meta("closed") === "false") await this.state.storage.setAlarm(Math.min(expiry, Date.now() + 300000));
  }
}
