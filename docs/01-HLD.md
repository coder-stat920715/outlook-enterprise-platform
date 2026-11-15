# 01 – High-Level Design

## 1. Goals and constraints
Enterprise e-mail / calendar platform for **millions of mailboxes** across three regions (APAC, EMEA, AMER) that must **degrade gracefully, never cascade**. The design is shaped by the failure pattern of the Nov 12–13 2026 outage: a slow identity/control-plane dependency → handshake failures → clients retry in lock-step → health checks eject pods → survivors overload → pods restart cold → reconnect storm.

| Concern | Decision |
|---|---|
| Runtime | Java 21, Spring Boot 3.4, **virtual threads** (`spring.threads.virtual.enabled=true`) |
| Edge | NGINX (TLS, static SPA immutable caching, signed-URL attachment CDN cache, PUT-only storage ingress) → **Envoy** (least-request, 60 s slow start, panic threshold 75 %) → Spring Cloud Gateway |
| Data | PostgreSQL 16, **16 logical shards** (2 physical hosts here), consistent hashing on `(tenant_id, mailbox_id)`; RANGE(month) → HASH(mailbox) partitioning |
| Async | Kafka (durable, keyed per mailbox), tiered store-and-forward retry, DLQ |
| Cache / coordination | Redis (SWR L2, delta watermarks, idempotency, free/busy bitmaps, gate flags, Pub/Sub), Redisson locks |
| Search | OpenSearch, tenant-routed + mandatory tenant filters |
| Objects | MinIO/S3 via presigned PUT, signed CDN GET |

## 2. Component view
```mermaid
flowchart LR
  C[Clients<br/>Web / Desktop / Mobile] --> NG[NGINX edge<br/>TLS • SPA cache • CDN cache • PUT-only storage]
  NG --> EV[Envoy LB<br/>least-request • slow-start 60s<br/>panic threshold 75%]
  EV --> G1[Gateway 1] & G2[Gateway 2]
  subgraph Gateway[Spring Cloud Gateway]
    G1
    G2
  end
  G1 & G2 -->|panic-floor LB| MAIL[Mail service xN]
  G1 & G2 --> CAL[Calendar service]
  G1 & G2 --> NOTI[Notification service<br/>SSE + WebSocket]
  G1 & G2 -. introspection (SWR cached) .-> IDP[(Identity Provider)]
  G1 & G2 --> R[(Redis)]
  MAIL --> PG[(PostgreSQL shards 00..15<br/>+ directory DB)]
  CAL --> PG
  MAIL <--> K{{Kafka}}
  CAL --> K
  K --> NOTI
  NOTI <--> R
  MAIL --> OS[(OpenSearch)]
  MAIL --> S3[(MinIO / S3)]
  NG -->|secure_link| S3
  CAL --> R
```

## 3. Request path and resilience layers
```mermaid
flowchart TD
  A[Request] --> B[NGINX: limit_conn, bounded next_upstream 2 tries/3s]
  B --> C[Envoy: LEAST_REQUEST, outlier ejection ≤25%, 1 retry max]
  C --> D[Gateway WebFilter: adaptive concurrency limiter<br/>CRITICAL > INTERACTIVE > NORMAL > BACKGROUND]
  D --> E[Per-IP token bucket Bucket4j/Redis - before auth]
  E --> F[Auth: local exp check → SWR introspection cache<br/>stale-if-error ≤ token exp]
  F --> G[Region gate: OPEN / DRAINING / CLOSED / RAMPING:p]
  G --> H[Per-user + per-tenant buckets]
  H --> I[Circuit breaker + panic-floor LB]
  I --> J[Service servlet filter: adaptive limiter + VT scheduler-lag probe]
  J --> K[Shard circuit breaker + bulkhead → PostgreSQL]
```

### Outage mitigations → implementation map
| Failure mode | Mitigation | Where |
|---|---|---|
| IdP / routing plane slow or down | Stale-while-revalidate + stale-if-error cache (L1 Caffeine, L2 Redis), never beyond token `exp`; fail-fast 503 + `Retry-After` for unseen tokens; 1.5 s upstream timeout | `StaleWhileRevalidateCache`, `TokenValidator`, `TenantRoutingCache` |
| Synchronised retries | Full-jitter exponential backoff everywhere; randomised `Retry-After`; SSE `retry:` hint jittered; reference client policy with initial spread | `JitterBackoff` |
| Health-check ejection cascade | Liveness = JVM only; readiness = local warm-up only; dependency state informational; active probes with hysteresis; **minimum-healthy floor / panic mode** (>25 % unhealthy ⇒ stop ejecting) in Envoy and in the gateway LB | `JvmLivenessIndicator`, `PanicFloorInstanceListSupplier`, `envoy.yaml` |
| Overload | Gradient2/AIMD adaptive concurrency limiter + priority shedding; virtual-thread scheduler-lag probe as congestion signal | `AimdConcurrencyLimiter`, `VirtualThreadLagMonitor` |
| Cold-restart death spiral | Limit ceiling ramps 5 %→100 % over 60 s; Envoy slow-start; readiness warm-up hold; connection admission control on notification pods | limiter, `envoy.yaml`, `ReconnectAdmission` |
| Shard degradation | Per-shard circuit breaker + bulkhead; Kafka store-and-forward (retry tier 1 → tier 2 → DLQ at 48 h) | `ShardExecutor`, `TieredRetryRouter` |
| Regional recovery | Phased region gate (drain / ramp) via Redis flags + admin API | `RegionGateService`, `RegionGateAdminController` |
| Noisy neighbour | Per-tenant token bucket + per-tenant routing keys | `RateLimitFilters` |

## 4. Data flows
### 4.1 Send mail (202 means durable)
```mermaid
sequenceDiagram
  participant U as Client
  participant GW as Gateway
  participant M as Mail API
  participant K as Kafka
  participant O as Outbound consumer
  participant S as Shard (sender)
  participant I as Inbound consumer
  participant R as Shard (recipient)
  U->>GW: POST /v1/mailboxes/{id}/messages/send (Idempotency-Key)
  GW->>M: + X-Tenant-ID, X-User-Mailbox-ID (from token)
  M->>M: validate, RBAC (SendAs/OnBehalf), idempotency SETNX
  M->>K: mail.outbound (acks=all, idempotent producer)
  M-->>U: 202 {messageId, state: QUEUED}
  K->>O: record
  O->>S: Sent copy (SENDING) + delta log (tx)
  O->>K: mail.inbound per internal recipient
  O->>O: SMTP relay simulation for external recipients
  O->>S: Sent copy → DELIVERED
  K->>I: mail.inbound
  I->>R: rules → insert + counters + delta seq (one tx, idempotent)
  I->>K: sync.events + search.indexer
```
Failure at any step publishes a `RetryEnvelope` to `mail.retry.tier1` (attempts 1–3, jitter base 15 s) then `tier2` (base 5 min, cap 30 min), then `mail.dlq` after 48 h. Offsets are acknowledged only after processing **or** durable hand-off to a retry tier.

### 4.2 Delta sync + push
```mermaid
sequenceDiagram
  participant C as Client
  participant N as Notification pod
  participant K as Kafka sync.events
  participant R as Redis
  participant M as Mail API
  C->>N: GET /v1/notifications/stream (SSE, admission-controlled)
  M->>K: sync.events (after commit)
  K->>N: relay consumer
  N->>R: PUBLISH push:{tenant}:{mailbox}
  R-->>N: every pod receives, local fan-out
  N-->>C: event: change (id = change_seq)
  C->>M: GET /messages/delta?deltaToken=…
  M->>R: watermark == token ? → empty (no DB)
  M-->>C: changes[], nextDeltaToken
```

## 5. Capacity estimation (order-of-magnitude, planning numbers)
Assumptions: 5 M mailboxes, 40 received + 10 sent messages/mailbox/day, average stored message 60 KB body+headers (attachments in object storage), 5-year retention, 3× peak/avg.

| Quantity | Estimate |
|---|---|
| Messages/day | 5 M × 50 = **250 M/day** ≈ 2.9 k/s avg, ~9 k/s peak (each = 1 sender copy + n recipient copies) |
| Row storage | 250 M × 60 KB ≈ **15 TB/day raw** → compress (TOAST/lz) ~3× ⇒ ~5 TB/day; this is why bodies > 1 MB and attachments sit in S3 and why retention/archival tiers matter |
| Shards | Target ≤ 4 TB hot data per physical PG host ⇒ start with 16 logical shards × (months of hot data) and re-balance logical→physical without re-hashing |
| Delta log | ~3 changes/message ⇒ 750 M rows/day; retained 30 days (purge by `min_retained_seq`), ~100 B/row ⇒ ~2 TB hot |
| Concurrent SSE | 30 % of 5 M online ⇒ 1.5 M connections ⇒ ~30 notification pods at 50 k conns |
| Kafka | 9 k msg/s peak × ~6 events/message ≈ 55 k events/s; 12–48 partitions per topic |
| Redis | watermarks 5 M × ~100 B; auth cache 1.5 M × ~300 B; free/busy 5 M × 12 B × days |

## 6. Security model
* Gateway authenticates (HS256 local or IdP introspection), **overwrites** `X-Tenant-ID`, `X-User-ID`, `X-User-Mailbox-ID`, `X-Region`; clients cannot spoof them.
* Services enforce mailbox-level RBAC (owner, FullAccess, SendAs, SendOnBehalf).
* Every SQL statement is tenant+mailbox scoped; OpenSearch queries carry mandatory server-side filters + routing.
* Attachments: presigned PUT (15 min), signed CDN GET (10 min, HMAC-style `secure_link`), anonymous GET on the storage ingress is denied.
* Production hardening still required: mTLS between tiers, secrets manager, WAF, network policies, `X-Admin-Key` replaced by real operator authN.

## 7. Known simplifications (explicit)
Single Kafka broker / Redis node / OpenSearch node in compose (use clusters, RF=3, `min.insync.replicas=2`); SMTP relay is simulated; calendar delegation is owner-only; attendee RSVP writes the organizer copy directly rather than via inbound iTIP processing; the NGINX `secure_link` secret is duplicated in `nginx.conf` and `outlook.mail.cdn-secret` and must be templated in real deployments.
