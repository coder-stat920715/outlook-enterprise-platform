#!/usr/bin/env python3
"""
Chaos simulation of the Nov 12-13 2026 outage pattern (stdlib only).

  python simulate_nov2026_outage.py --idp-only --port 9099      # run the mock Identity Provider (compose does this)
  python simulate_nov2026_outage.py --base http://localhost:8080 --idp http://localhost:9099 --admin-key change-me

Scenarios (each prints PASS/FAIL):
  1. IdP DEGRADED  : introspection takes 8s then fails. Already-seen tokens MUST keep working (stale-while-revalidate /
                     stale-if-error), new tokens MUST fail fast with 503 + Retry-After (never hang).
  2. RECONNECT STORM: 400 clients open SSE streams at once; excess MUST be told 503 + Retry-After (jittered, spread out).
  3. REGION DRAIN  : CLOSE APAC -> APAC tenant gets 503 + Retry-After, AMER unaffected; RAMP restores APAC gradually.
"""
import argparse, base64, json, statistics, threading, time, urllib.error, urllib.parse, urllib.request
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# ------------------------------------------------------------------------------------------------ mock IdP
STATE = {"latency_ms": 0, "fail": False}

class Idp(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def _send(self, code, body):
        data = json.dumps(body).encode()
        self.send_response(code); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(data))); self.end_headers(); self.wfile.write(data)
    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0)); body = self.rfile.read(n).decode()
        if self.path == "/admin/state":
            STATE.update(json.loads(body)); return self._send(200, STATE)
        if STATE["latency_ms"]: time.sleep(STATE["latency_ms"] / 1000)
        if STATE["fail"]: return self._send(503, {"error": "idp_unavailable"})
        token = urllib.parse.parse_qs(body).get("token", [""])[0]
        try: payload = json.loads(base64.urlsafe_b64decode(token.split(".")[1] + "=="))
        except Exception: return self._send(200, {"active": False})
        self._send(200, {"active": True, **payload})

def serve_idp(port):
    ThreadingHTTPServer(("0.0.0.0", port), Idp).serve_forever()

# ------------------------------------------------------------------------------------------------ helpers
def mint(tenant, mailbox, region, sub="user", ttl=3600):
    b = lambda d: base64.urlsafe_b64encode(json.dumps(d).encode()).rstrip(b"=").decode()
    return f"{b({'alg':'none'})}.{b({'sub':sub,'tid':tenant,'mid':mailbox,'region':region,'exp':int(time.time())+ttl,'jti':str(time.time_ns())})}.sig"

def call(method, url, token=None, body=None, headers=None, timeout=15):
    req = urllib.request.Request(url, method=method, data=json.dumps(body).encode() if body is not None else None)
    req.add_header("Content-Type", "application/json")
    if token: req.add_header("Authorization", "Bearer " + token)
    for k, v in (headers or {}).items(): req.add_header(k, v)
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r: return r.status, dict(r.headers), time.time() - t0
    except urllib.error.HTTPError as e: return e.code, dict(e.headers), time.time() - t0
    except Exception: return 0, {}, time.time() - t0

def verdict(name, ok, detail): print(f"[{'PASS' if ok else 'FAIL'}] {name}: {detail}"); return ok

# ------------------------------------------------------------------------------------------------ scenarios
def scenario_idp(a):
    T, M = "00000000-0000-0000-0000-000000000001", "10000000-0000-0000-0000-000000000001"
    url = f"{a.base}/v1/mailboxes/{M}/folders"
    known = [mint(T, M, "AMER", f"u{i}") for i in range(20)]
    for t in known: call("GET", url, t)                                   # warm the SWR cache while the IdP is healthy
    call("POST", a.idp + "/admin/state", body={"latency_ms": 8000, "fail": True})
    with ThreadPoolExecutor(60) as ex:
        old = list(ex.map(lambda i: call("GET", url, known[i % 20]), range(200)))
        new = list(ex.map(lambda i: call("GET", url, mint(T, M, "AMER", f"n{i}")), range(40)))
    call("POST", a.idp + "/admin/state", body={"latency_ms": 0, "fail": False})
    ok_old = sum(1 for s, _, _ in old if s != 503 and s != 401 and s != 0) / len(old)
    new_503 = [(s, h, d) for s, h, d in new if s == 503]
    fast = statistics.median(d for _, _, d in new) < 4.0
    r1 = verdict("cached tokens survive IdP outage", ok_old >= 0.95, f"{ok_old:.0%} of requests with previously-validated tokens were admitted")
    r2 = verdict("new tokens fail fast with Retry-After", len(new_503) == len(new) and all("Retry-After" in h or "retry-after" in h for _, h, _ in new_503) and fast,
                 f"{len(new_503)}/{len(new)} got 503, median latency {statistics.median(d for _, _, d in new):.2f}s")
    return r1 and r2

def scenario_storm(a):
    T, M = "00000000-0000-0000-0000-000000000001", "10000000-0000-0000-0000-000000000001"
    tok = mint(T, M, "AMER")
    def open_stream(_):
        s, h, d = call("GET", f"{a.base}/v1/notifications/stream?mailboxId={M}", tok, timeout=3)
        return s, h
    with ThreadPoolExecutor(200) as ex: res = list(ex.map(open_stream, range(400)))
    rejected = [(s, h) for s, h in res if s == 503]
    ra = sorted({int(h.get("Retry-After", h.get("retry-after", "0"))) for _, h in rejected if (h.get("Retry-After") or h.get("retry-after"))})
    return verdict("reconnect storm is admission-controlled", len(ra) >= 1 and (len(ra) > 3 or not rejected),
                   f"{len(rejected)}/400 told to retry later, {len(ra)} distinct jittered Retry-After values {ra[:8]}")

def scenario_region(a):
    T = "00000000-0000-0000-0000-000000000002"
    apac, amer = mint(T, "10000000-0000-0000-0000-000000000011", "APAC"), mint(T, "10000000-0000-0000-0000-000000000012", "AMER")
    adm = {"X-Admin-Key": a.admin_key, "Content-Type": "application/json"}
    call("PUT", f"{a.base}/gateway-admin/regions/APAC", body={"state": "CLOSED"}, headers=adm)
    time.sleep(3)                                                         # gate cache TTL is 2s
    s1, h1, _ = call("GET", f"{a.base}/v1/mailboxes/10000000-0000-0000-0000-000000000011/folders", apac)
    s2, _, _ = call("GET", f"{a.base}/v1/mailboxes/10000000-0000-0000-0000-000000000012/folders", amer)
    ok1 = verdict("closed region is shed with Retry-After", s1 == 503 and ("Retry-After" in h1 or "retry-after" in h1), f"APAC status={s1}")
    ok2 = verdict("other regions unaffected", s2 != 503, f"AMER status={s2}")
    call("PUT", f"{a.base}/gateway-admin/regions/APAC", body={"state": "RAMPING", "percent": 25}, headers=adm); time.sleep(3)
    codes = [call("GET", f"{a.base}/v1/mailboxes/10000000-0000-0000-0000-000000000011/folders", apac)[0] for _ in range(200)]
    admitted = sum(1 for c in codes if c != 503) / len(codes)
    ok3 = verdict("ramp admits a fraction", 0.10 <= admitted <= 0.45, f"RAMPING:25 admitted {admitted:.0%}")
    call("PUT", f"{a.base}/gateway-admin/regions/APAC", body={"state": "OPEN"}, headers=adm)
    return ok1 and ok2 and ok3

if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--idp-only", action="store_true"); ap.add_argument("--port", type=int, default=9099)
    ap.add_argument("--base", default="http://localhost:8080"); ap.add_argument("--idp", default="http://localhost:9099")
    ap.add_argument("--admin-key", default="change-me")
    a = ap.parse_args()
    if a.idp_only: serve_idp(a.port)
    else:
        results = [scenario_idp(a), scenario_storm(a), scenario_region(a)]
        print("\nOVERALL:", "PASS" if all(results) else "FAIL"); raise SystemExit(0 if all(results) else 1)
