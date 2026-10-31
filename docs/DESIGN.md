# Design note: Roster Sync

## Architecture

```mermaid
flowchart LR
    HR[Client HR system] -- "events (JWT: INTEGRATION)" --> API
    OPS[MoveInSync operator] -- "nightly CSV / approve (OPERATOR)" --> API
    ADM[Transport admin] -- "pickup point (ADMIN)" --> API

    subgraph App[roster-sync]
        API[REST + JWT tenant check] --> ING[IngestService]
        API --> REC[ReconciliationService]
        API --> TRN[TransportService]
        ING --> CORE["Validator + EventApplier<br/>(pure Java)"]
        REC --> ING
        RELAY[OutboxRelay<br/>every 1 s] --> H1[BookingCancellationHandler]
        RELAY --> H2[LoggingEventHandler]
    end

    ING -- "employee + outbox<br/>one transaction" --> DB[(PostgreSQL)]
    TRN --> DB
    REC -- streaming reads --> DB
    RELAY -- "claim SKIP LOCKED" --> DB
    H1 -- cancel + free seats --> DB
    PROM[Prometheus] -- scrape --> API
```

## Consistency model

Per employee, state converges to the event with the highest source version, regardless of
delivery order or duplication; writes for one employee are serialised by a Postgres advisory
lock, and every change commits atomically with its outbox event. HR-owned and transport-owned
fields have separate writers and separate versions, so neither can overwrite the other.
Downstream consumers are eventually consistent: events are delivered at least once, normally
within ~1 s (`roster_outbox_lag_seconds`), and consumers are idempotent, giving an exactly-once
effect. Reconciliation never deactivates on stale information: each deactivation is
conditional on the row being unchanged since the diff.

## Hardest flow: nightly reconciliation with the safety brake

```mermaid
sequenceDiagram
    participant Op as Operator
    participant API
    participant Rec as ReconciliationService
    participant DB as PostgreSQL

    Op->>API: POST /reconciliations (CSV, JWT OPERATOR)
    API->>Rec: start(tenant, file)
    Rec->>DB: insert run (RUNNING)
    Note over Rec: Pass 1: stream file → 16,384 bucket fingerprints
    Rec->>DB: Pass A: stream active (id, hash) → DB fingerprints
    Note over Rec: compare → dirty buckets
    Rec->>DB: Pass B: load hashes for dirty buckets only
    Note over Rec: Pass 2: presence + change count (no writes yet)
    alt missing ≥ 30% of active
        Rec->>DB: run = BLOCKED, summary(missing=N)
        Rec-->>Op: BLOCKED + alert metric
        Op->>API: POST /approve (approver = JWT subject)
        API->>DB: BLOCKED → RUNNING (atomic, once)
        Note over Rec: re-run the diff with brake released
    end
    loop each changed row (Pass 3)
        Rec->>DB: lock → version-checked upsert + outbox (one tx)
    end
    loop each missing employee
        Rec->>DB: deactivate IF hash unchanged + outbox (one tx)
    end
    Rec->>DB: run = COMPLETED, summary
    Rec-->>Op: report
```

## Failure modes

| What fails | What the user sees | How the system recovers |
|---|---|---|
| Duplicate or replayed HR event | `DUPLICATE` in batch result; nothing changes | Version check; no event published |
| Events arrive out of order | Older event reported `STALE` | Highest version wins; tombstones block late CREATE after DEACTIVATE |
| Malformed record in a batch | That record `REJECTED` with field codes; rest applied | Per-record validation and transactions |
| App crashes after commit, before publish | Nothing; downstream briefly behind | Relay finds unpublished row on restart and publishes it |
| App crashes mid-transaction | Request fails; HR retries | Transaction rolls back employee + outbox together; retry is idempotent |
| Consumer fails (e.g., booking DB error) | Leaver's bookings cancelled a few seconds late | Savepoint rollback, retry with attempts count; dead-letter after 5 + alert |
| Event delivered twice | Nothing | Consumers idempotent (only CONFIRMED bookings are cancelled) |
| Nightly file truncated | Run `BLOCKED`, operator alerted with missing count | No writes made; human approves or discards |
| HR updates an employee during reconciliation | Nothing | Deactivation is conditional on unchanged hash; skipped and counted |
| Postgres down | API returns 500 / health DOWN; records not lost on HR side | Fail fast; HR retries once DB is back; idempotent |
| Two relay instances | Nothing | `FOR UPDATE SKIP LOCKED`: each row claimed by one relay |
| Token for another tenant | 403 | Tenant claim checked against URL on every request |