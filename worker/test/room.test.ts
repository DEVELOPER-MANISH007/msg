import { runInDurableObject } from "cloudflare:test";
import { env } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import { Room } from "../src/index";

const digest = async (text: string) => [...new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text)))].map(x => x.toString(16).padStart(2, "0")).join("");
const token = (n: number) => `test-credential-${n}-123456789012345678901234`;
const roomId = () => `test-room-${crypto.randomUUID()}`;
const packets = new WeakMap<WebSocket, { queue: any[]; waiters: Array<(packet: any) => void> }>();

async function create(id = roomId(), expiry = Date.now() + 60000) {
  const stub = env.ROOMS.get(env.ROOMS.idFromName(id));
  const response = await stub.fetch("https://room.internal/create", { method: "POST", body: JSON.stringify({ id, expiry, inviteHash: await digest("invite"), participants: [{ id: "alice", credentialHash: await digest(token(1)) }] }) });
  expect(response.status).toBe(200);
  return stub;
}

async function join(stub: DurableObjectStub) {
  return stub.fetch("https://room.internal/join", { method: "POST", body: JSON.stringify({ invitationHash: await digest("invite"), participantId: "bob", credentialHash: await digest(token(2)) }) });
}

async function connect(stub: DurableObjectStub, bearer: string): Promise<WebSocket> {
  const response = await stub.fetch(new Request("https://room.internal/socket", { headers: { Upgrade: "websocket", Authorization: `Bearer ${bearer}` } }));
  if (response.status !== 101 || !response.webSocket) throw new Error(`WebSocket rejected: ${response.status}`);
  const state = { queue: [] as any[], waiters: [] as Array<(packet: any) => void> }; packets.set(response.webSocket, state);
  response.webSocket.addEventListener("message", event => {
    const packet = JSON.parse(String(event.data));
    const waiter = state.waiters.shift();
    if (waiter) waiter(packet); else state.queue.push(packet);
  });
  response.webSocket.accept();
  return response.webSocket;
}

function nextPacket(socket: WebSocket, timeoutMs = 3000): Promise<any> {
  const state = packets.get(socket) ?? { queue: [], waiters: [] };
  if (state.queue.length) return Promise.resolve(state.queue.shift());
  return new Promise((resolve, reject) => {
    let settled = false;
    const waiter = (packet: any) => { if (settled) return; settled = true; clearTimeout(timeout); resolve(packet); };
    const timeout = setTimeout(() => { settled = true; const index = state.waiters.indexOf(waiter); if (index >= 0) state.waiters.splice(index, 1); reject(new Error("Timed out waiting for WebSocket packet")); }, timeoutMs);
    state.waiters.push(waiter);
  });
}
async function nextMatching(socket: WebSocket, predicate: (packet: any) => boolean): Promise<any> {
  while (true) { const packet = await nextPacket(socket); if (predicate(packet)) return packet; }
}

describe("peer-first anonymous room signaling", () => {
  it("admits one invitation, rejects replay, and authorizes room entry", async () => {
    const stub = await create();
    const wrong = await stub.fetch("https://room.internal/join", { method: "POST", body: JSON.stringify({ invitationHash: await digest("wrong"), participantId: "mallory", credentialHash: await digest(token(9)) }) });
    expect(wrong.status).toBe(403);
    expect((await join(stub)).status).toBe(200);
    expect((await join(stub)).status).toBe(403);
    expect((await stub.fetch(new Request("https://room.internal/socket", { headers: { Upgrade: "websocket", Authorization: `Bearer ${token(9)}` } }))).status).toBe(401);
  });

  it("rejects text, keys, and file relay payloads and keeps no chat or media records", async () => {
    const stub = await create(); await join(stub);
    const alice = await connect(stub, token(1)); const bob = await connect(stub, token(2));
    expect(await nextMatching(alice, packet => packet.type === "peer-ready")).toMatchObject({ participantId: "bob" });
    expect(await nextMatching(bob, packet => packet.type === "peer-ready")).toMatchObject({ participantId: "alice" });
    for (const type of ["text", "key-exchange", "attachment", "file-start", "file-chunk", "file-end"]) {
      const rejected = nextMatching(alice, packet => packet.type === "error");
      alice.send(JSON.stringify({ type, id: "never-store", ciphertext: "opaque" }));
      expect(await rejected).toEqual({ type: "error", code: "direct-channel-required" });
    }
    expect((await stub.fetch("https://room.internal/attachment-init", { method: "POST", headers: { Authorization: `Bearer ${token(1)}` } })).status).toBe(404);
    expect((await stub.fetch("https://room.internal/attachments/object", { method: "GET", headers: { Authorization: `Bearer ${token(1)}` } })).status).toBe(404);
    const tables = await runInDurableObject<Room, string[]>(stub, (_object, state) => [...state.storage.sql.exec<{ name: string }>("SELECT name FROM sqlite_master WHERE type='table'")].map(row => row.name));
    expect(tables).not.toContain("messages"); expect(tables).not.toContain("files");
    alice.close(); bob.close();
  });

  it("relays only bounded signaling and rejects non-member call signaling", async () => {
    const stub = await create(); await join(stub);
    const alice = await connect(stub, token(1)); const bob = await connect(stub, token(2));
    await nextMatching(alice, packet => packet.type === "peer-ready"); await nextMatching(bob, packet => packet.type === "peer-ready");
    const offer = nextMatching(bob, packet => packet.type === "call-offer");
    alice.send(JSON.stringify({ type: "call-offer", sdp: "v=0\r\nsecure-offer" }));
    expect(await offer).toMatchObject({ type: "call-offer", sdp: "v=0\r\nsecure-offer", senderId: "alice" });
    expect((await stub.fetch("https://room.internal/exit", { method: "POST" })).status).toBe(401);
    const invalid = nextMatching(bob, packet => packet.type === "error");
    bob.send(JSON.stringify({ type: "call-answer", sdp: "x".repeat(65001) }));
    expect(await invalid).toMatchObject({ type: "error", code: "invalid_packet" });
    alice.close(); bob.close();
  });

  it("does not queue signaling for an offline recipient and replays the sender purge on recipient reconnect", async () => {
    const stub = await create(); await join(stub);
    const alice = await connect(stub, token(1)); await nextPacket(alice);
    const bob = await connect(stub, token(2));
    await nextMatching(alice, packet => packet.type === "peer-ready");
    await nextMatching(bob, packet => packet.type === "peer-ready");
    bob.close();
    await new Promise(resolve => setTimeout(resolve, 30));
    alice.send(JSON.stringify({ type: "offer", sdp: "must-not-queue" }));
    expect((await stub.fetch("https://room.internal/exit", { method: "POST", headers: { Authorization: `Bearer ${token(1)}` } })).status).toBe(200);
    const reconnect = await connect(stub, token(2));
    expect(await nextMatching(reconnect, packet => packet.type === "purge-sender")).toMatchObject({ type: "purge-sender", senderId: "alice" });
    const failedDirectFile = nextMatching(reconnect, packet => packet.type === "error");
    reconnect.send(JSON.stringify({ type: "file-start", id: "failed-transfer", ciphertext: "not-relayed" }));
    expect(await failedDirectFile).toEqual({ type: "error", code: "direct-channel-required" });
    expect((await stub.fetch(new Request("https://room.internal/socket", { headers: { Upgrade: "websocket", Authorization: `Bearer ${token(1)}` } }))).status).toBe(401);
    const tables = await runInDurableObject<Room, string[]>(stub, (_object, state) => [...state.storage.sql.exec<{ name: string }>("SELECT name FROM sqlite_master WHERE type='table'")].map(row => row.name));
    expect(tables).not.toContain("messages");
    await stub.fetch("https://room.internal/exit", { method: "POST", headers: { Authorization: `Bearer ${token(2)}` } });
    alice.close(); reconnect.close();
  });

  it("makes exit idempotent and expires an abandoned room with retryable metadata cleanup", async () => {
    const stub = await create();
    const aliceHeaders = { Authorization: `Bearer ${token(1)}` };
    expect((await stub.fetch("https://room.internal/exit", { method: "POST", headers: aliceHeaders })).status).toBe(200);
    const tasksAfterFirstExit = await runInDurableObject<Room, number>(stub, (_object, storage) => [...storage.storage.sql.exec("SELECT id FROM cleanup_tasks")].length);
    expect((await stub.fetch("https://room.internal/exit", { method: "POST", headers: aliceHeaders })).status).toBe(401);
    const tasksAfterDuplicateExit = await runInDurableObject<Room, number>(stub, (_object, storage) => [...storage.storage.sql.exec("SELECT id FROM cleanup_tasks")].length);
    expect(tasksAfterDuplicateExit).toBe(tasksAfterFirstExit);

    const expiring = await create(roomId(), Date.now() - 1);
    await runInDurableObject<Room, void>(expiring, async (object) => { await object.alarm(); });
    const state = await runInDurableObject<Room, { closed: string | undefined; retries: number }>(expiring, (_object, storage) => ({
      closed: [...storage.storage.sql.exec<{ value: string }>("SELECT value FROM room_meta WHERE key='closed'")][0]?.value,
      retries: [...storage.storage.sql.exec<{ retries: number }>("SELECT retries FROM cleanup_tasks")][0]?.retries ?? 0,
    }));
    expect(state.closed).toBe("true");
    expect(state.retries).toBe(0);
    await runInDurableObject(expiring, (_object, storage) => storage.storage.sql.exec("UPDATE cleanup_tasks SET next_at=?", Date.now() - 1));
    await runInDurableObject<Room, void>(expiring, async (object) => { await object.alarm(); });
    const retried = await runInDurableObject<Room, number>(expiring, (_object, storage) => [...storage.storage.sql.exec<{ retries: number }>("SELECT retries FROM cleanup_tasks")][0]?.retries ?? 0);
    expect(retried).toBe(1);
  });
});
