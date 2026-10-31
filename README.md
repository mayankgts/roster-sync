# Roster Sync: HR roster ingestion that stays correct under duplicates, reordering and broken files

MoveInSync Backend Case Study 5. Keeps employee records correct while HR systems send
changes twice, out of order, with bad data, and sometimes as truncated nightly files.

## Quick start

Requires Docker Desktop.

```bash
docker compose up --build
```

API on `http://localhost:8080`, Prometheus on `http://localhost:9090`.

Run the tests (needs Docker running; uses Testcontainers):

```bash
./mvnw test
```

### Demo users (demo only; production would use an identity provider)

| Username | Password | Role | Tenant |
|---|---|---|---|
| hr-acme | hr-acme-pass | INTEGRATION | acme |
| admin-acme | admin-acme-pass | ADMIN | acme |
| ops-acme | ops-acme-pass | OPERATOR | acme |
| E1001 | e1001-pass | EMPLOYEE | acme |
| hr-globex | hr-globex-pass | INTEGRATION | globex |

## API

| Method | Path | Role | Purpose |
|---|---|---|---|
| POST | `/api/v1/auth/token` | none | Get a JWT |
| POST | `/api/v1/tenants/{t}/employee-events` | INTEGRATION | Ingest up to 10,000 HR events |
| GET | `/api/v1/tenants/{t}/employees/{id}` | ADMIN, OPERATOR, or the employee themselves | Read one employee |
| PUT | `/api/v1/tenants/{t}/employees/{id}/transport` | ADMIN | Set pickup point / special assistance |
| POST | `/api/v1/tenants/{t}/reconciliations` | OPERATOR | Upload nightly CSV export |
| POST | `/api/v1/tenants/{t}/reconciliations/{run}/approve` | OPERATOR | Release a safety-braked run |
| GET | `/api/v1/tenants/{t}/reconciliations/{run}` | OPERATOR | Run status and summary |
| GET | `/actuator/health`, `/actuator/prometheus` | none | Health and metrics |

Example (PowerShell): `. .\requests\demo.ps1`, then

```powershell
curl.exe -X POST http://localhost:8080/api/v1/tenants/acme/employee-events `
  -H "Authorization: Bearer $HR" -H "Content-Type: application/json" `
  --data-binary "@requests/sample-batch.json"
```

## How it works (one paragraph)

Every HR event carries the source system's version and a full snapshot of the HR-owned fields.
Per employee, the highest version wins: equal is a duplicate, lower is stale. Each record is
applied in its own transaction under a per-employee advisory lock, and the employee row and an
outbox event are written in that same transaction. A relay publishes outbox events
(at-least-once) to idempotent consumers, such as booking cancellation for leavers. The nightly
export is diffed with bucketed hashes in a few streaming passes; if it would deactivate 30% or
more of active employees, the run stops and waits for an operator.
Details: [docs/DESIGN.md](docs/DESIGN.md).

## Hard rules and the tests that prove them

| Rule | Test |
|---|---|
| Replaying the same feed twice changes nothing | `IngestIntegrationTest.replayingTheSameFeedTwiceChangesNothing` |
| v7 before v6: v6 ignored as stale | `IngestIntegrationTest.transferV7ArrivingBeforeShiftChangeV6Wins` |
| Final state is independent of delivery order | `EventApplierTest.deliveryOrderDoesNotAffectFinalState` |
| One bad record never fails a batch | `IngestIntegrationTest.oneBadRecordDoesNotFailTheBatch` |
| HR never overwrites transport-owned fields | `IngestIntegrationTest.hrUpdateNeverOverwritesTransportOwnedFields` |
| Concurrent updates end at the highest version, one event per change | `IngestIntegrationTest.concurrentUpdatesForOneEmployeeEndAtHighestVersion` |
| Crash after commit, before publish: event delivered exactly once | `OutboxRelayIntegrationTest.crashAfterCommitBeforePublish_eventIsDeliveredExactlyOnceAfterRestart` |
| Crash between DB write and outbox write: nothing persists | `OutboxRelayIntegrationTest.crashBetweenEmployeeWriteAndOutboxWrite_rollsBackBoth` |
| Truncated export: brake stops the run, nobody deactivated | `ReconciliationIntegrationTest.truncatedFileTriggersSafetyBrakeAndChangesNothing` |
| Approval releases a braked run exactly once | `ReconciliationIntegrationTest.approvalResumesABlockedRunExactlyOnce` |
| Leaver's future bookings cancelled, seats freed, redelivery harmless | `BookingCancellationIntegrationTest.*` |
| One tenant can never access another's data | `SecurityIntegrationTest.integrationCannotPushToAnotherTenant` |
| No personal data in logs or error responses | `RecordValidatorTest`, `IngestService`, `HrFields.toString()` |

## Benchmarks

<paste the table from Step 14>

## Assumptions

- Source versions are unique and increase per employee. Two events with the same version are treated as the same event.
- HR events carry the full HR-owned record (snapshot, not delta).
- The nightly export lists every **active** employee. Absence means the employee has left.
- A later HR event for an inactive employee reactivates them (rehire).
- Reconciliation deactivation does not invent a source version.

## Trade-offs and what I left out

- **Consistency over availability for writes.** Each record commits synchronously with its outbox event; if Postgres is down, ingest fails fast and HR retries (idempotent).
- **Per-record transactions** isolate failures but cap throughput at commit rate; bulk apply would be faster for mass changes.
- **In-process event publisher.** The `EventPublisher` interface is the seam for Kafka/SQS.
- **HS256 with demo users.** Production: an identity provider with RS256/JWKS.
- **Reconciliation runs synchronously** in the request; production would queue it and return a run id.
- **Spring Boot 3.5** (stable, well documented) rather than 4.x.
- Left out: login rate limiting, admin UI, multi-region, CSV fields containing newlines.