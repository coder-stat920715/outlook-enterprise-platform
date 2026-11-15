# Enterprise Outlook System – Spring Boot reference implementation

Mail + calendar platform designed so that a slow dependency or a restart wave **degrades service instead of cascading** (the Nov 12–13 2026 outage pattern). Java 21, Spring Boot 3.4.1, Spring Cloud 2024.0.0, virtual threads.

```
NGINX (edge/CDN) → Envoy (LB, slow-start, panic floor) → Gateway ×2 → mail-service ×2 | calendar | notification
                                   PostgreSQL ×16 logical shards + directory • Kafka • Redis • OpenSearch • MinIO
```

## Modules
| Module | Purpose |
|---|---|
| `outlook-common` | sharding, adaptive limiter, SWR cache, jitter, health, **SQL migrations** (`db/shard`, `db/directory`) |
| `outlook-gateway` | auth (SWR-cached introspection / HS256), Bucket4j rate limits, region drain/ramp, panic-floor LB, load shedding |
| `outlook-mail-service` | messages, folders, send pipeline (Kafka, tiered retry, DLQ), delta sync, attachments, search, rules, OOF, delegation, GAL, tasks |
| `outlook-calendar-service` | RRULE (DST-aware), meeting lifecycle + ICS, free/busy bitmaps, room booking (lock + version + exclusion constraint) |
| `outlook-notification-service` | SSE + WebSocket push, Redis Pub/Sub fan-out, reconnect admission control |
| `docs/` | `01-HLD.md`, `02-LLD.md`, `03-DATABASE.md` (real EXPLAIN output) |
| `infrastructure/` | nginx, envoy, postgres init, prometheus/grafana, chaos-test |

## Run
```bash
mvn -q clean package -DskipTests          # needs JDK 21 + Maven + access to Maven Central
docker compose up --build                  # edge on http://localhost:8080
```
`mail-1` migrates all 16 shards + the directory DB on startup (Flyway).

### Smoke test
```bash
python3 - <<'PY'   # mint a token accepted by the mock IdP
import json,base64,time
b=lambda d: base64.urlsafe_b64encode(json.dumps(d).encode()).rstrip(b"=").decode()
T="00000000-0000-0000-0000-000000000001"
print(f"{b({'alg':'none'})}.{b({'sub':'alice','tid':T,'mid':'10000000-0000-0000-0000-00000000000a','region':'AMER','exp':int(time.time())+3600})}.sig")
PY
# provision (use the printed token as $TOKEN; mailbox id is returned)
curl -s -XPOST localhost:8080/v1/admin/mailboxes -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
     -d '{"email":"alice@corp.test","displayName":"Alice"}'
```
Note: mailbox ids come from provisioning; the token's `mid` claim must equal the provisioned mailbox id to act as that user.

### Outage / chaos simulation
```bash
python3 infrastructure/chaos-test/simulate_nov2026_outage.py --base http://localhost:8080 --idp http://localhost:9099
```
Scenarios: IdP outage (cached tokens survive, new tokens fail fast with `Retry-After`), reconnect storm (admission control), region drain/ramp.

## API testing (Postman)
`postman/` contains a 139-request collection with assertions, an environment, a token minting helper, a stress-test compose overlay and `TESTING_GUIDE.md` (step-by-step expected results, manual resilience drills).

## Tests
`mvn test` runs unit tests; `*IT` classes need Docker (Testcontainers): partition pruning (`PartitionPruningIT`) and distributed rate limiting (`RateLimiterServiceIT`).

## Verification status – please read
* The SQL schemas were executed on a real **PostgreSQL 16** and the query plans in `docs/03-DATABASE.md` are real output.
* The dependency-free classes (limiter, jitter, consistent hashing, RRULE/ICS, state machines) were compiled with `javac` 21 and exercised.
* The **Maven build of the full project was NOT run** in the authoring sandbox (Maven Central was unreachable), so Spring/Kafka/Bucket4j/Redisson/MinIO API usage is unverified by a compiler. Expect to fix a few small compile or version issues on first `mvn package` (most likely areas: Bucket4j lettuce builder API, Spring Cloud LoadBalancer supplier wiring, MinIO checked exceptions).
* Not production-ready as shipped: single-node Kafka/Redis/OpenSearch in compose, simulated SMTP, default secrets (`change-me*`), no mTLS/WAF; see HLD §7.
