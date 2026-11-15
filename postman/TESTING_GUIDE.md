# Testing guide – Enterprise Outlook platform (Postman)

Files in this folder

| File | Purpose |
|---|---|
| `Enterprise-Outlook.postman_collection.json` | 139 requests / 16 folders, each with assertions (`pm.test`) and a description |
| `Enterprise-Outlook.local.postman_environment.json` | `baseUrl`, `idpUrl`, `envoyAdminUrl`, `adminKey`, tenant ids |
| `mint_token.py` | creates a bearer token for curl / websocat |
| `docker-compose.stress-test.yml` | overlay with tiny limits so rate-limit, shedding and reconnect-storm protections trigger by hand |
| `build_collection.py`, `postman_lib.js` | generator (edit + re-run to change the collection) |

> **Honesty note.** The collection's scripts were syntax-checked and exercised against a mock Postman sandbox, and the helper library (token minting, UUIDv3 identical to Java's `nameUUIDFromBytes`) was verified. The full stack itself has **not** been run end-to-end by me (the Maven build could not run in my sandbox). Treat the first run as a shake-down: a failing assertion may reveal an application bug or a mismatch between my expectation and the code — each request's description states what it expects.

---------------------------------------------------------------------------------------------------

## 1. Prerequisites
* Docker + Docker Compose v2, JDK 21, Maven 3.9+, Postman desktop app (v10+; scripts use `await`), Python 3 (for `mint_token.py`), optional: `curl`, `websocat`, `hey`.
* ~8 GB RAM free for the whole stack.

## 2. Start the system
```bash
cd outlook-enterprise-platform
mvn -q clean package -DskipTests
docker compose up --build -d
docker compose ps                       # wait until mail-1, mail-2, gateway-1/2, envoy, nginx are "running"
docker compose logs -f mail-1           # wait for "Schema up to date" lines (16 shards + directory) and "Started MailServiceApplication"
```
Readiness checks (≈ 60–90 s after start):
```bash
curl -s localhost:8080/lb-health                           # -> ok          (NGINX)
curl -s localhost:9901/clusters | grep gateways | grep health_flags   # both gateways "healthy"
curl -s localhost:9099/admin/state -XPOST -d '{"latency_ms":0,"fail":false}'   # mock IdP
```
If `envoy` reports gateways as `/failed_active_hc`, wait – gateways keep readiness OUT_OF_SERVICE for the first 8 s and Envoy needs 2 successful checks.

## 3. Import into Postman
1. **Import** → select both JSON files in `postman/`.
2. Top-right environment selector → **Enterprise Outlook – Local Docker**.
3. Check environment values: `baseUrl=http://localhost:8080`, `idpUrl=http://localhost:9099`, `envoyAdminUrl=http://localhost:9901`, `adminKey=change-me` (must equal `GATEWAY_ADMIN_KEY` in compose).
4. Nothing else to configure: tokens are minted automatically by the *collection pre-request script* before every request (unsigned JWT-shaped tokens accepted by the mock IdP; `exp` = next-but-one hour so the gateway's token cache gets hits). Mailbox ids are captured from provisioning responses and stored as collection variables.

> Why unsigned tokens? The compose stack runs the gateway in `introspect` mode against `mock-idp`, which accepts any structurally valid token and echoes its claims. In production the IdP does real signature validation. To test local signature verification instead, set `AUTH_MODE=hmac` and mint HS256 tokens with the secret in `GatewayProperties`.

## 4. Running the collection
### 4.1 Run everything (recommended first run)
**Collection ⋯ → Run collection**
| Setting | Value |
|---|---|
| Run order | as listed (never shuffle – requests depend on variables saved by earlier ones) |
| Iterations | 1 |
| Delay | 300 ms |
| Persist responses/variables | ON, keep variable values ON |
| **Deselect** | `4.8 [MANUAL]`, `12.2 [MANUAL …]`, `13.14 Rate-limit probe` |

Expected duration ≈ 5–6 minutes (deliberate waits: async delivery 3–6 s, delegation cache 32 s, SWR demo 65 s).
Expected result: all assertions pass. Anything red → see §6.

### 4.2 Run a single folder
Folders 02–15 need the variables from **01 Provisioning** (run it once; variables persist in the collection). Folders 04/05/06/07 additionally need `bobMessageId` etc. from folder 03, so run 00 → 03 first.

---------------------------------------------------------------------------------------------------

## 5. Step-by-step walkthrough with expected results

### Folder 00 – Edge, infra & auth guard
| Step | What it does | Expected |
|---|---|---|
| 0.1 | `GET /lb-health` on NGINX | 200 `ok` |
| 0.2 | Envoy admin `/clusters` | both gateway hosts listed in cluster `gateways` |
| 0.3 | Reset mock IdP to healthy | 200 `{"latency_ms":0,"fail":false}` |
| 0.4 | Open all region gates | 200 |
| 0.5 | Folder list without token | **401** (gateway rejects anonymous calls) |

### Folder 01 – Provisioning
Creates `alice@corp.test`, `bob@corp.test` ("Bob Builder"), shared `support@corp.test`, `carol@corp.test` (region **APAC**), and `dave@other.test` in **tenant 2**. Each returns `mailboxId` → saved as `aliceId`, `bobId`, `sharedId`, `carolId`, `daveId`. 1.5 re-provisions Alice and must return the **same** id (idempotent). Also sets `runId` (unique per run so searches/subjects don't collide).
Behind the scenes: directory DB row + mailbox row + 5 folders + sync-state row on the mailbox's shard (`consistent-hash(tenant:mailbox)`).

### Folder 02 – Folders & tenant isolation
* 2.1/2.2 list well-known folders (INBOX, SENT, DRAFTS, ARCHIVE, DELETED) and store their ids.
* 2.3 creates a custom folder.
* 2.4 a **tenant-2** token targeting Alice's mailbox → **403**.
* 2.5 sends spoofed `X-Tenant-ID`/`X-User-Mailbox-ID` headers → still answered as Alice (**gateway overwrites identity headers**).

### Folder 03 – Send / receive / threads / pagination
| Step | Expected | What it proves |
|---|---|---|
| 3.1 send | 202, `state=QUEUED` | message durably in Kafka `mail.outbound` |
| 3.2 same `Idempotency-Key` | 200, header `Idempotent-Replayed: true`, same `messageId` | Redis idempotency |
| 3.3 no recipients | 422 | validation |
| 3.4 Bob's inbox (after 3 s) | message present, unread | outbound → inbound pipeline |
| 3.5 detail | `deliveryState=DELIVERED`, body present | |
| 3.6/3.7 Alice's Sent | sent copy exists, state `DELIVERED` | state machine SENDING→DELIVERED |
| 3.8–3.10 counters | unread count drops by exactly 1 after PATCH `isRead` | transactional folder counters |
| 3.11 reply with `inReplyToMessageId` | same `conversationId` as original | threading |
| 3.12 thread | ≥ 2 messages, one conversation | |
| 3.13 move to Archive | `folderId` = archive | MOVED change |
| 3.14–3.17 | 5 messages; page 1 (limit 2) has `nextCursor`; page 2 disjoint & older; `unreadOnly` returns only unread | keyset pagination, partial-index path |

Optional DB check while running 3.x: `docker compose exec pg-shard-a psql -U outlook -d shard_00 -c "select count(*) from mail_messages"` (the mailbox's shard may be any of 00–15; loop over them).

### Folder 04 – Delta sync
| Step | Expected |
|---|---|
| 4.1 initial (no token) | `fullSyncRequired=true`, a token |
| 4.2 + 4.3 send then delta with token0 | ≥ 1 change, ascending `changeSeq`, new token |
| 4.4 delta with newest token | **0 changes**, token unchanged (Redis watermark fast path) |
| 4.5 tampered token | 400 |
| 4.6 Alice's token used on Bob's mailbox | 400 (token MAC bound to tenant+mailbox) |
| 4.7 `limit=1` | exactly 1 change, `hasMore=true` |
| **4.8 manual – expired token → 410** | see below |

**4.8 procedure** – simulate log retention purge, then send 4.8:
```bash
ALICE=<aliceId from Postman collection variables>
for i in $(seq 0 7);  do docker compose exec -T pg-shard-a psql -U outlook -d shard_0$i  -c "UPDATE mailbox_sync_state SET min_retained_seq = last_seq + 10 WHERE mailbox_id='$ALICE'"; done
for i in 08 09 10 11 12 13 14 15; do docker compose exec -T pg-shard-b psql -U outlook -d shard_$i -c "UPDATE mailbox_sync_state SET min_retained_seq = last_seq + 10 WHERE mailbox_id='$ALICE'"; done
```
Expected for 4.8: **410 Gone** ("perform a full resync"). Undo: set `min_retained_seq = 0` the same way.

### Folder 05 – Attachments
1. 5.1 returns `attachmentId` + presigned `uploadUrl` (host `localhost:9000`, NGINX storage ingress).
2. 5.2 PUTs 23 bytes straight to storage (no API involved) → 200.
3. 5.3 `complete` → 204 (server `stat`s the object and records its real size).
4. 5.4 signed CDN URL `http://localhost:8080/cdn/attachments/<tenant>/<mailbox>/<id>?md5=…&expires=…`.
5. 5.5 download → 200 content `Hello attachment world!`; 5.6 second download → header `X-Cache-Status: HIT`.
6. 5.7 tampered signature → **403**.
7. 5.8–5.10 send mail with attachment; Bob sees `hasAttachments=true` and can obtain his own download URL for the *same stored object* (no blob copy; Bob's attachment id = UUIDv3 of `attId|bobMailboxId`).
Negative check (manual): `curl -i http://localhost:9000/outlook-attachments/<objectKey>` → 403 (storage ingress is PUT-only).

### Folder 06 – Search
6.1 Bob finds mail by the unique token (indexing is async – allow up to 5 s); 6.2 Carol gets 0 hits; 6.3 Bob on Alice's mailbox → 403.

### Folder 07 – Rules & Out-of-Office
* 7.1–7.4 rule "subject contains INVOICE → Archive, mark read, category Finance": the mail is in Archive (read) and absent from Inbox.
* 7.5–7.9 OOF: Alice receives **exactly one** auto-reply ("Out of Office: Question …"); a second mail from Alice does **not** trigger another (per-sender dedupe); auto-replies never trigger auto-replies (loop guard).

### Folder 08 – Delegation / RBAC
| Step | Expected |
|---|---|
| 8.1 shared mailbox grants Alice `SEND_AS` | 204 |
| 8.2/8.3 Alice sends via `/mailboxes/{shared}/messages/send` | Bob sees sender `support@corp.test` |
| 8.4 Bob sends as shared | 403 |
| 8.5 / 8.6 Bob / Alice(SEND_AS only) read shared mailbox | 403 / 403 |
| 8.7–8.8 grant `FULL_ACCESS`, read after 32 s | 200 (permission cache TTL is 30 s per pod) |
| 8.9 revoke | 204 |
Manual extension: grant Bob `SEND_ON_BEHALF`, wait 32 s, send as shared → mail shows Bob's address (on behalf of support@).

### Folder 09 – Contacts, GAL, tasks
GAL `Ali` (prefix) finds Alice; `Bob Bilder` (typo) finds Bob through trigram fuzzy search; tenant-2 sees an empty GAL; flagging a message creates an OPEN "Follow up: …" task and un-flagging closes it.

### Folder 10 – Calendar
| Step | Expected |
|---|---|
| 10.1–10.2 single event | 201 `DRAFT`; appears in window query |
| 10.3–10.5 | 422 for bad IANA zone, end<start, bad RRULE |
| 10.6 weekly Monday 09:00 America/New_York + Bob | 201, `SENT`, Bob `NEEDS_ACTION` |
| **10.7 DST** | occurrences `2026-10-26T13:00:00Z`, `2026-11-02T14:00:00Z`, `2026-11-09T14:00:00Z` (09:00 local before and after DST ends on 1 Nov) |
| 10.8–10.9 | Bob has a TENTATIVE copy and an "Invitation: DST Weekly Sync" mail |
| 10.10–10.12 | Bob accepts → organizer shows `ACCEPTED`; Bob's copy becomes BUSY |
| 10.13 | update with stale `version` → **409** |
| 10.14 | update with current version → `UPDATED`, `sequenceNo=1` |
| 10.15 | Bob mutating Alice's calendar → 403 |
| 10.16–10.18 | cancel → disappears for Alice and Bob |
| 10.19–10.20 | free/busy: union busy 15:00–16:30Z; common free **14:00–15:00** and **16:30–18:00** |
| 10.21 | cross-tenant free/busy → 403 (policy off). Enable with `CROSS_TENANT_FREEBUSY=true` on calendar and expect 200 |
| 10.22 | window > 62 days → 422 |

### Folder 11 – Room booking
* 11.2 book 10–11 → 201; 11.3 overlap → **409**; 11.4 adjacent 11–12 → 201 (ranges are half-open).
* **11.5 race**: five simultaneous bookings for 13–14 → exactly **one** 201 and four 409 (Redisson lock + `EXCLUDE USING gist`); the request itself then gets 409.
* 11.6 reschedule with `version 0` → 200 (`version 1`); 11.7 same version again → 409; 11.8 reschedule onto an occupied slot → 409; 11.9 cancel; 11.10 slot is bookable again.
Manual proof of the DB guarantee: stop Redis (`docker compose stop redis`) and repeat 11.3 → still 409 (lock acquisition fails with 5xx/409, never a double booking); `docker compose start redis` afterwards.

### Folder 12 – Notifications
Automated: 12.1 Alice cannot subscribe to Bob's stream (403).
**Manual live push test (SSE):**
```bash
TOKEN=$(python3 postman/mint_token.py --mailbox <aliceId> --sub alice)
curl -N -H "Authorization: Bearer $TOKEN" -H "Accept: text/event-stream" \
     "http://localhost:8080/v1/notifications/stream?mailboxId=<aliceId>"
```
Expect `event: ready` (with a jittered `retry:` value). In another terminal send Alice a mail (Bob → Alice, e.g. Postman 3.11 variant or a curl send). Within ~1 s you must see `event: change` with `data:{"changeSeq":…,"entityType":"MESSAGE",…}` and `id:` equal to the change sequence. Heartbeat comments (`: hb`) arrive every 20 s. Then call delta (4.3 style) with your last token to fetch the actual changes.
**WebSocket:**
```bash
websocat -H "Authorization: Bearer $TOKEN" "ws://localhost:8080/ws/v1/notifications?mailboxId=<aliceId>"
```
**Reconnect-storm admission control** (uses the stress overlay, see §7.3).

### Folder 13 – Gateway resilience
| Step | Expected |
|---|---|
| 13.1 / 13.2 | malformed / expired token → 401 (expired is rejected locally, IdP never called) |
| 13.3 | wrong `X-Admin-Key` → 403 |
| 13.4 | lists APAC/EMEA/AMER gates |
| 13.5–13.7 | APAC **CLOSED** → Carol gets **503 + Retry-After (5–60 s) + `X-Region-State: APAC:CLOSED`**; Alice (AMER) unaffected |
| 13.8–13.10 | **DRAINING** → interactive read allowed (200), background (search) shed (503) |
| 13.11 | **RAMPING 50 %** → 10–30 of 40 requests admitted |
| 13.12–13.13 | staged ramp 25→50→100→OPEN completes in ~8 s |
| 13.14 | rate-limit probe – see §7.1 |

### Folder 14 – Identity-provider outage drill (the Nov-12 scenario)
| Step | Expected |
|---|---|
| 14.1 | warm token cache |
| 14.2 | IdP now answers after 8 s with failures |
| 14.3 | cached token still **200**, fast |
| 14.4 | **never-seen token → 503 in < 4 s with `Retry-After`** (does not hang on the 8 s IdP) |
| 14.5 | after 65 s the entry is past its fresh window but within stale-while-revalidate → still 200 |
| 14.6–14.7 | restore IdP; unseen token works again |
Always finish with 14.6 (or folder 15) so later runs start healthy.

### Folder 15 – Cleanup
Gates OPEN, IdP healthy.

---------------------------------------------------------------------------------------------------

## 7. Manual / environment-assisted tests (not automatable in a plain Postman run)

### 7.1 Rate limiting (Bucket4j on Redis) – step 13.14
1. Apply the overlay: `docker compose -f docker-compose.yml -f postman/docker-compose.stress-test.yml up -d gateway-1 gateway-2` (per-IP burst 30, refill 1/s).
2. In Postman run **only** request 13.14 with **Iterations 100, Delay 0 ms**.
3. Expected: the first ~30 return 200, then **429** with `Retry-After` and `X-RateLimit-Scope: ip`; both gateways share one bucket (Redis), so alternating gateways does not give extra quota.
4. Revert: `docker compose up -d gateway-1 gateway-2`.
Per-user (`300 burst / 50 s`) and per-tenant (`20 000 / 4 000 s`) tiers: lower via `OUTLOOK_GATEWAY_RATELIMIT_USER_CAPACITY=5` etc. and expect `X-RateLimit-Scope: user|tenant`.

### 7.2 Adaptive load shedding
With the overlay (max concurrency 16):
```bash
TOKEN=$(python3 postman/mint_token.py --mailbox <aliceId>)
hey -z 20s -c 200 -H "Authorization: Bearer $TOKEN" -H "X-Outlook-Priority: background" \
    "http://localhost:8080/v1/mailboxes/<aliceId>/search?q=quasar"
```
Expected: many **503** with `X-Shed-Reason: adaptive-concurrency` and random `Retry-After` 2–20 s. Run a second `hey` for interactive traffic (`/folders`) at the same time – it must keep a much higher success rate (priority admission: BACKGROUND ≤ 50 % of the limit, INTERACTIVE ≤ 100 %).

### 7.3 Reconnect-storm admission control
Overlay active (5 new SSE connections/s, 50 max):
```bash
TOKEN=$(python3 postman/mint_token.py --mailbox <aliceId>)
seq 1 100 | xargs -P 100 -I{} curl -s -o /dev/null -m 2 -w "%{http_code} %header{retry-after}\n" \
   -H "Authorization: Bearer $TOKEN" "http://localhost:8080/v1/notifications/stream?mailboxId=<aliceId>" | sort | uniq -c
```
Expected: a small number of timeouts (accepted streams, 200 kept open) and many `503 <n>` with **different** `Retry-After` values 3–45 (jitter spreads the retry wave).

### 7.4 Panic floor / no ejection cascade (Envoy)
```bash
docker compose stop gateway-2                       # 50 % of hosts unhealthy  (> 25 %)
sleep 20; curl -s localhost:9901/stats | grep -E "gateways.*(healthy_panic|membership_healthy)"
```
Expected: `cluster.gateways.lb_healthy_panic` counter increases while `membership_healthy` is 1 of 2 – Envoy keeps routing to **all** hosts (panic mode) instead of concentrating all load on the survivor. Run folder 02 requests meanwhile: they still succeed (Envoy retries once on connect failure). `docker compose start gateway-2`, then watch slow start: `curl -s localhost:9901/clusters | grep gateway-2` – the restarted gateway receives a gradually increasing share over 60 s.

### 7.5 Shard degradation and store-and-forward
```bash
docker compose stop pg-shard-b          # shards 08–15 down
```
1. Reads/writes on mailboxes hashed to 08–15 → **503** with `Retry-After` (shard circuit breaker + 2 s connect timeout – fast failure, not a hang). Mailboxes on 00–07 are unaffected.
2. A **send** to a recipient on a dead shard still returns **202**; the inbound delivery fails and is parked in `mail.retry.tier1` (15 s–2 min jitter) then `tier2`:
   `docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 --topic mail.retry.tier1 --from-beginning --max-messages 3`
3. `docker compose start pg-shard-b` → the parked mail is delivered automatically after its `notBefore` time; Bob (or the recipient) sees it, **no loss, no duplicates** (idempotent insert).
(Which shard a mailbox lives on: the integration test `AimdAndJitterTest#shardingIsStableAndBalanced` shows the hash; in practice try several mailboxes.)

### 7.6 Pod restart / cold start
`docker compose restart mail-2` while running the folder-03 requests in a loop: no failed user-visible request beyond brief 503s; after restart the pod's concurrency ceiling ramps 5 % → 100 % over 60 s (`outlook_limiter_limit` metric at `http://localhost:9090` Prometheus), and its readiness stays OUT_OF_SERVICE for the first 8 s.

### 7.7 Liveness vs readiness (no restart on dependency loss)
`docker compose stop redis` → `docker compose exec mail-1 curl -s localhost:8081/actuator/health/liveness` is still `UP` (JVM-only); `/actuator/health/dependencies` shows `redis: DEGRADED`; readiness remains `UP`. Restart Redis afterwards.

## 8. Variable reference (collection variables, auto-managed)
`runId`, `aliceId`, `bobId`, `sharedId`, `carolId`, `daveId`, folder ids (`aliceInboxId`, `bobArchiveId`, …), `sentMessageId`, `bobMessageId`, `convId`, `deltaToken0/1`, `attId`, `uploadUrl`, `downloadUrl`, `recurId`, `bobEventId`, `roomId`, `booking1/2`, tokens (`tokenAlice` … regenerated before every request). To start from scratch clear the collection variables (or import the collection again) and re-run 00 → 01.

## 9. Troubleshooting
| Symptom | Likely cause / fix |
|---|---|
| Everything 401 | gateway cannot reach `mock-idp` – `docker compose logs gateway-1`; token malformed (clock skew > 1 h) |
| 503 on first requests | services still warming (8 s hold + migrations); retry after `docker compose ps` is stable |
| 3.4 shows empty inbox | async delivery slower than the 3 s wait – re-send the request; check `docker compose logs mail-1 | grep -i inbound` |
| 5.2 fails with 403 `SignatureDoesNotMatch` | `MINIO_PUBLIC_ENDPOINT` must be exactly `http://localhost:9000` (signature covers the Host header) |
| 5.5 403 | `outlook.mail.cdn-secret` ≠ secret in `nginx.conf` (`secure_link_md5`) |
| 6.1 empty | OpenSearch not ready/indexed yet – wait and re-run |
| 8.8 403 | permission cache on the other mail pod – wait 30 s |
| 10.x 404 `mailbox` | provisioning not run in this collection session (folder 01) |
| 13.x gate has no effect | gate cache is 2 s – the collection waits 3 s; also confirm `X-Admin-Key` equals `GATEWAY_ADMIN_KEY` |
| Postman "await is only valid…" | update Postman (v10+) or run via Newman: `newman run Enterprise-Outlook.postman_collection.json -e Enterprise-Outlook.local.postman_environment.json --delay-request 300 --folder "00 Edge, infra & auth guard" ...` |

### Running with Newman (CI)
```bash
npm i -g newman
newman run postman/Enterprise-Outlook.postman_collection.json \
  -e postman/Enterprise-Outlook.local.postman_environment.json \
  --delay-request 300 --timeout-request 120000 --reporters cli,junit \
  --reporter-junit-export newman-results.xml
```
(Exclude the manual/rate-limit items by running folders individually with `--folder`; the long waits make the full run ≈ 6 minutes.)
