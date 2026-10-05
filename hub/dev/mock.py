#!/usr/bin/env python3
"""Local mock of Supabase (auth/rest/realtime ws), the GCP metadata server and FCM.
One port, stdlib only. After a socket joins, it emits one chat row ~2s later and prints
every FCM send to stdout (data keys only, never values logged by the hub itself).
Usage: python3 dev/mock.py 9400"""
import asyncio, base64, hashlib, json, struct, sys, time

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 9400
GUILD = "11111111-1111-1111-1111-111111111111"
USERS = {"tok-alice": ("u-alice", "alice"), "tok-bob": ("u-bob", "bob")}
GUILD_ROW = {"guild_id": GUILD, "guilds": {"name": "Mock Guild", "slug": "mock-guild"}}
NEXT = {"n": 0}

def who(headers):
    return USERS.get(headers.get("authorization", "").removeprefix("Bearer "))

async def ws_send(w, obj):
    data = json.dumps(obj).encode()
    n = len(data)
    hdr = bytes([0x81]) + (bytes([n]) if n < 126 else bytes([126]) + struct.pack(">H", n))
    w.write(hdr + data); await w.drain()

async def ws_read(r):
    b = await r.readexactly(2)
    op, ln = b[0] & 0xF, b[1] & 0x7F
    if ln == 126: ln = struct.unpack(">H", await r.readexactly(2))[0]
    elif ln == 127: ln = struct.unpack(">Q", await r.readexactly(8))[0]
    mask = await r.readexactly(4) if b[1] & 0x80 else b"\0\0\0\0"
    data = bytearray(await r.readexactly(ln))
    for i in range(ln): data[i] ^= mask[i % 4]
    return op, bytes(data)

async def ws_session(r, w):
    print("[mock] ws connected", flush=True)
    async def emit():
        await asyncio.sleep(2)
        NEXT["n"] += 1
        rec = {"id": f"msg-{NEXT['n']}-{int(time.time())}", "content": "hello @alice from the mock", "user_id": "u-bob",
               "guild_id": GUILD, "created_at": "2026-10-06T04:00:00.000000+00:00"}
        await ws_send(w, [None, None, f"realtime:guild_chat:{GUILD}", "postgres_changes",
                          {"ids": [1], "data": {"type": "INSERT", "record": rec}}])
        await ws_send(w, [])  # defensive: empty array frame
    try:
        while True:
            op, data = await ws_read(r)
            if op == 8: break
            if op != 1: continue
            jr, ref, topic, event, payload = json.loads(data)
            print(f"[mock] ws <- {event}", flush=True)
            if event == "phx_join":
                await ws_send(w, [jr, ref, topic, "phx_reply", {"status": "ok", "response": {}}])
                asyncio.create_task(emit())
            elif event == "heartbeat":
                await ws_send(w, [None, ref, "phoenix", "phx_reply", {"status": "ok", "response": {}}])
    except (asyncio.IncompleteReadError, ConnectionError):
        pass
    print("[mock] ws closed", flush=True)

async def handle(r, w):
    line = await r.readline()
    if not line: return
    method, path, _ = line.decode().split(" ", 2)
    headers = {}
    while (l := (await r.readline()).decode().strip()):
        k, v = l.split(":", 1); headers[k.strip().lower()] = v.strip()
    body = await r.readexactly(int(headers.get("content-length", 0))) if headers.get("content-length") else b""
    route = path.split("?")[0]

    if headers.get("upgrade", "").lower() == "websocket":
        acc = base64.b64encode(hashlib.sha1((headers["sec-websocket-key"] + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()).decode()
        w.write(f"HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: {acc}\r\n\r\n".encode())
        await w.drain(); await ws_session(r, w); w.close(); return

    code, out = 200, None
    u = who(headers)
    if route == "/auth/v1/user": out = {"id": u[0]} if u else None
    elif route == "/rest/v1/guild_members": out = [GUILD_ROW] if u else None
    elif route == "/rest/v1/profiles": out = [{"username": u[1]}] if u else None
    elif route == "/rest/v1/guild_messages": out = [] if u else None
    elif route == "/auth/v1/token":
        out = {"access_token": "tok-alice", "refresh_token": "refresh-2", "expires_at": int(time.time()) + 3600, "user": {"id": "u-alice"}}
    elif route.endswith("/service-accounts/default/token"): out = {"access_token": "mock-sa", "expires_in": 3000}
    elif route.endswith("/messages:send"):
        m = json.loads(body)["message"]
        print(f"[mock] FCM send token={m['token'][:6]}... type={m['data']['type']} keys={sorted(m['data'])} mention={m['data'].get('mention')} user={m['data'].get('username')!r}", flush=True)
        out = {"name": "ok"}
    elif route.startswith("/guilds/") and route.endswith("/members"):
        out = None; payload = b'x"profile":{"username":"bob"},"user_id":"u-bob"}'
        w.write(b"HTTP/1.1 200 OK\r\nContent-Length: %d\r\nConnection: close\r\n\r\n" % len(payload) + payload); await w.drain(); w.close(); return
    if out is None: code, out = 401, {"error": "unauthorized"}
    data = json.dumps(out).encode()
    w.write(f"HTTP/1.1 {code} X\r\nContent-Type: application/json\r\nContent-Length: {len(data)}\r\nConnection: close\r\n\r\n".encode() + data)
    await w.drain(); w.close()

async def main():
    srv = await asyncio.start_server(handle, "127.0.0.1", PORT)
    print(f"[mock] listening 127.0.0.1:{PORT}", flush=True)
    async with srv: await srv.serve_forever()
asyncio.run(main())
