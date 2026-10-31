# Silent, self-running demo for screen recording.
# Prereq (fresh data):  docker compose down -v ; docker compose up --build -d   then wait ~30 s
# Run:                  powershell -ExecutionPolicy Bypass -File .\requests\demo-run.ps1
# Slower reading pace:  add  -Pace 1.3

param([double]$Pace = 1.0)

$Base = "http://localhost:8080"
$Req  = $PSScriptRoot
Set-Location (Split-Path $PSScriptRoot -Parent)

# ---------- display helpers ----------

function Wait-Secs($s) { Start-Sleep -Milliseconds ([int]($s * 1000 * $Pace)) }

function Caption($title, [string[]]$lines) {
    Clear-Host
    Write-Host ""
    Write-Host ("=" * 76) -ForegroundColor DarkCyan
    Write-Host "  $title" -ForegroundColor Cyan
    Write-Host ("=" * 76) -ForegroundColor DarkCyan
    foreach ($line in $lines) { Write-Host "  $line" -ForegroundColor Gray }
    Write-Host ""
    Wait-Secs 5
}

function Step($text)  { Write-Host ""; Write-Host "> $text" -ForegroundColor Yellow; Wait-Secs 1.5 }
function Point($text) { Write-Host ""; Write-Host "  => $text" -ForegroundColor Magenta; Wait-Secs 3.5 }

# ---------- API helpers ----------

function Get-Token($user, $pass) {
    $body = @{ username = $user; password = $pass } | ConvertTo-Json
    (Invoke-RestMethod -Method Post -Uri "$Base/api/v1/auth/token" -ContentType "application/json" -Body $body).accessToken
}

function Invoke-Api($Method, $Path, $Token, $File) {
    $params = @{ Method = $Method; Uri = "$Base$Path"; ContentType = "application/json"; Headers = @{} }
    if ($Token) { $params.Headers.Authorization = "Bearer $Token" }
    if ($File)  { $params.InFile = $File }
    try {
        $body = Invoke-RestMethod @params
        return [pscustomobject]@{ Status = 200; Body = $body }
    } catch {
        $err = $_
        $code = 0
        if ($err.Exception.Response) { $code = [int]$err.Exception.Response.StatusCode }
        $detail = $null
        if ($err.ErrorDetails -and $err.ErrorDetails.Message) {
            try { $detail = ($err.ErrorDetails.Message | ConvertFrom-Json).detail } catch { $detail = $err.ErrorDetails.Message }
        }
        return [pscustomobject]@{ Status = $code; Body = $detail }
    }
}

function Show-Status($r) {
    $color = if ($r.Status -eq 200) { "Green" } else { "Yellow" }
    Write-Host "  HTTP $($r.Status)" -ForegroundColor $color
    if ($r.Status -ne 200 -and $r.Body) { Write-Host "  detail: $($r.Body)" -ForegroundColor DarkYellow }
    Wait-Secs 2
}

function Show-Batch($r) {
    Show-Status $r
    if ($r.Status -ne 200) { return }
    $c = $r.Body.counts
    Write-Host ("  APPLIED={0}  NO_CHANGE={1}  DUPLICATE={2}  STALE={3}  REJECTED={4}  FAILED={5}" -f `
        $c.APPLIED, $c.NO_CHANGE, $c.DUPLICATE, $c.STALE, $c.REJECTED, $c.FAILED) -ForegroundColor White
    foreach ($p in $r.Body.problems) {
        $errs = ($p.errors | ForEach-Object { "$($_.field):$($_.code)" }) -join ", "
        Write-Host "  problem: record #$($p.index) $($p.employeeId) $($p.outcome) -> $errs" -ForegroundColor DarkYellow
    }
    Wait-Secs 2.5
}

function Show-Employee($r) {
    Show-Status $r
    if ($r.Status -ne 200) { return }
    $e = $r.Body
    $pickup = if ($e.transport.pickupPoint) { $e.transport.pickupPoint } else { "-" }
    Write-Host ("  {0}  status={1}  hrVersion={2}  office={3}  shift={4}  pickup='{5}'  adminVersion={6}" -f `
        $e.employeeId, $e.status, $e.hrVersion, $e.hr.officeCode, $e.hr.shiftCode, $pickup, $e.adminVersion) -ForegroundColor White
    Wait-Secs 2.5
}

function Show-Run($r) {
    Show-Status $r
    if ($r.Status -ne 200) { return }
    $run = $r.Body.run
    $s = $run.summary
    $by = if ($run.approvedBy) { $run.approvedBy } else { "-" }
    Write-Host ("  run #{0}  status={1}  approvedBy={2}" -f $run.id, $run.status, $by) -ForegroundColor White
    Write-Host ("  fileRows={0}  activeBefore={1}  missing={2} ({3:P0})  brake threshold={4:P0}" -f `
        $s.fileRows, $s.activeBefore, $s.missing, $s.missingRatio, $s.brakeThreshold) -ForegroundColor White
    Write-Host ("  applied={0}  staleOrDuplicate={1}  deactivated={2}" -f $s.applied, $s.staleOrDuplicate, $s.deactivated) -ForegroundColor White
    Wait-Secs 3
}

function Show-Health {
    try {
        $h = Invoke-RestMethod "$Base/actuator/health"
        Write-Host "  health: $($h.status)" -ForegroundColor Green
    } catch {
        Write-Host "  health: DOWN" -ForegroundColor Red
    }
    Wait-Secs 2
}

function Sql($query) { docker exec roster-postgres psql -U roster -d roster -t -A -F " | " -c $query }

function Show-Booking {
    Sql "SELECT b.employee_id, b.status, coalesce(b.cancel_reason, '-'), t.seats_booked FROM booking b JOIN trip t ON t.id = b.trip_id" |
        ForEach-Object { if ($_) { Write-Host "  booking: $_   (employee | status | reason | seats booked on trip)" -ForegroundColor White } }
    Wait-Secs 2.5
}

# =====================================================================

Caption "HR Roster Sync  |  MoveInSync Backend Case Study 5" @(
    "Keeps employee records correct when HR updates arrive twice, out of order,",
    "with bad data, or as a truncated nightly file.",
    "",
    "Java 21 + Spring Boot + PostgreSQL, started with: docker compose up --build")

Step "Health check"
Show-Health

Step "Log in demo users: HR system, transport admin, operator, employee"
$HR    = Get-Token "hr-acme"    "hr-acme-pass"
$ADMIN = Get-Token "admin-acme" "admin-acme-pass"
$OPS   = Get-Token "ops-acme"   "ops-acme-pass"
$EMP   = Get-Token "E1001"      "e1001-pass"
Point "4 JWTs issued. Each carries a role and a tenant."

# ---------------------------------------------------------------------
Caption "1. Authentication and tenant isolation" @(
    "Every request needs a signed JWT.",
    "The tenant in the URL must match the tenant inside the token, on every request.")

Step "GET an employee with NO token"
Show-Status (Invoke-Api GET "/api/v1/tenants/acme/employees/E1001")

Step "acme's HR token tries to push events into tenant 'globex'"
Show-Status (Invoke-Api POST "/api/v1/tenants/globex/employee-events" $HR -File "$Req\sample-batch.json")

Step "Employee E1001 reads their own record, then a colleague's (E1002)"
Show-Status (Invoke-Api GET "/api/v1/tenants/acme/employees/E1001" $EMP)
Show-Status (Invoke-Api GET "/api/v1/tenants/acme/employees/E1002" $EMP)
Point "No token -> 401. Other tenant or someone else's record -> 403."

# ---------------------------------------------------------------------
Caption "2. Ingest a batch: per-record validation + idempotency" @(
    "4 records: 2 valid, 1 exact duplicate (same version),",
    "1 with an invalid email, phone and office code.")

Step "HR system sends the batch"
Show-Batch (Invoke-Api POST "/api/v1/tenants/acme/employee-events" $HR -File "$Req\sample-batch.json")
Point "Bad record rejected with field codes only (no personal data). The rest still applied."

Step "HR sends the SAME batch again (retry / replay)"
Show-Batch (Invoke-Api POST "/api/v1/tenants/acme/employee-events" $HR -File "$Req\sample-batch.json")
Point "Replay changes nothing. Idempotency key = tenant + employee + source version."

# ---------------------------------------------------------------------
Caption "3. Out-of-order delivery" @(
    "E1002: version 7 (transfer to BLR01, NIGHT shift) arrives",
    "BEFORE version 6 (HYD01, DAY shift).")

Step "HR sends v7 then v6"
Show-Batch (Invoke-Api POST "/api/v1/tenants/acme/employee-events" $HR -File "$Req\out-of-order.json")

Step "Read E1002"
Show-Employee (Invoke-Api GET "/api/v1/tenants/acme/employees/E1002" $ADMIN)
Point "v6 ignored as STALE. E1002 ends at the v7 state: highest version always wins."

# ---------------------------------------------------------------------
Caption "4. Field ownership" @(
    "HR owns name, email, office, shift. The transport admin owns the pickup point.",
    "An HR update must never overwrite an admin-owned field.")

Step "Transport admin sets E1001's pickup point"
Show-Employee (Invoke-Api PUT "/api/v1/tenants/acme/employees/E1001/transport" $ADMIN -File "$Req\transport.json")

Step "HR transfers E1001 to HYD01 (version 2)"
Show-Batch (Invoke-Api POST "/api/v1/tenants/acme/employee-events" $HR -File "$Req\e1001-transfer.json")

Step "Read E1001"
Show-Employee (Invoke-Api GET "/api/v1/tenants/acme/employees/E1001" $ADMIN)
Point "Office changed by HR, pickup point kept. Separate writers, separate versions."

# ---------------------------------------------------------------------
Caption "5. Nightly reconciliation with a safety brake" @(
    "The nightly export lists every active employee; anyone missing has left.",
    "If 30% or more would be deactivated, the file is probably broken:",
    "stop, change nothing, and wait for an operator.")

Step "Setup: E1002 has a confirmed cab booking for tomorrow"
Sql "INSERT INTO trip (tenant_id, office_code, departs_at, capacity, seats_booked) VALUES ('acme','BLR01', now() + interval '1 day', 4, 1); INSERT INTO booking (tenant_id, employee_id, trip_id, status) VALUES ('acme','E1002', (SELECT max(id) FROM trip), 'CONFIRMED');" | Out-Null
Show-Booking

Step "Operator uploads a truncated export (only E1001 in it, so 50% missing)"
$json = curl.exe -s -X POST "$Base/api/v1/tenants/acme/reconciliations" -H "Authorization: Bearer $OPS" -F "file=@$Req\nightly-truncated.csv"
$upload = [pscustomobject]@{ Status = 200; Body = ($json | ConvertFrom-Json) }
Show-Run $upload
$RunId = $upload.Body.run.id

Step "Is E1002 still active?"
Show-Employee (Invoke-Api GET "/api/v1/tenants/acme/employees/E1002" $ADMIN)
Point "BLOCKED before any write. Nobody deactivated; operator sees how many are missing."

Step "Operator reviews and approves run #$RunId (approver is taken from the JWT)"
Show-Run (Invoke-Api POST "/api/v1/tenants/acme/reconciliations/$RunId/approve" $OPS)
Point "File had E1001 at v1 but DB has v2: ignored as stale, nobody is rolled back."

Step "Approve the same run a second time"
Show-Status (Invoke-Api POST "/api/v1/tenants/acme/reconciliations/$RunId/approve" $OPS)

Wait-Secs 2
Step "E1002 and the booking, after approval"
Show-Employee (Invoke-Api GET "/api/v1/tenants/acme/employees/E1002" $ADMIN)
Show-Booking
Point "Deactivation -> outbox event -> future booking cancelled and seat freed."

# ---------------------------------------------------------------------
Caption "6. Live failure: the database goes down" @(
    "PostgreSQL is stopped while HR sends a new joiner.")

Step "docker compose stop postgres"
docker compose stop postgres 2>&1 | Out-Null
Write-Host "  postgres stopped" -ForegroundColor Red
Wait-Secs 1.5

Step "HR sends new joiner E1004"
Show-Batch (Invoke-Api POST "/api/v1/tenants/acme/employee-events" $HR -File "$Req\new-joiner.json")

Step "Health check"
Show-Health
Point "Fails fast with a generic error: nothing half-written, nothing leaked."

Step "docker compose start postgres"
docker compose start postgres 2>&1 | Out-Null
for ($i = 0; $i -lt 30; $i++) {
    try { if ((Invoke-RestMethod "$Base/actuator/health").status -eq "UP") { break } } catch { }
    Start-Sleep -Seconds 1
}
Show-Health

Step "HR retries the SAME request"
Show-Batch (Invoke-Api POST "/api/v1/tenants/acme/employee-events" $HR -File "$Req\new-joiner.json")

Step "...and retries once more"
Show-Batch (Invoke-Api POST "/api/v1/tenants/acme/employee-events" $HR -File "$Req\new-joiner.json")
Point "Applied exactly once; the extra retry is a DUPLICATE. Blind retries are safe."

# ---------------------------------------------------------------------
Caption "7. On-call metrics (Prometheus)" @(
    "Outcomes per tenant, outbox queue depth and lag, dead letters,",
    "safety-brake triggers and bookings cancelled.")

Step "GET /actuator/prometheus (filtered)"
curl.exe -s "$Base/actuator/prometheus" |
    Select-String '^roster_(ingest_records_total|outbox_pending|outbox_lag_seconds|outbox_dead_lettered|reconciliation_brake_triggered_total|bookings_cancelled_total)' |
    ForEach-Object { Write-Host "  $_" -ForegroundColor White }
Wait-Secs 4
Point "Alert rules for lag, dead letters, failure rate and the brake are in ops/alerts.yml."

# ---------------------------------------------------------------------
Clear-Host
Write-Host ""
Write-Host ("=" * 76) -ForegroundColor DarkCyan
Write-Host "  Summary" -ForegroundColor Cyan
Write-Host ("=" * 76) -ForegroundColor DarkCyan
@(
    "Idempotent ingest              replay changes nothing",
    "Out-of-order safe              highest source version wins (proved over 50 random orders)",
    "Field ownership                HR and transport admin can never overwrite each other",
    "Transactional outbox           DB change and its event commit together; crash-tested",
    "Nightly reconciliation         bucketed hash diff; 200k rows in <X> s with a 256 MB heap",
    "Safety brake                   30%+ missing -> BLOCKED until an operator approves",
    "Leaver side effects            future bookings cancelled, seats freed, redelivery-safe",
    "Security                       JWT roles + tenant isolation on every request",
    "",
    "Every hard rule has an automated test (unit, Testcontainers, concurrency, crash, security).",
    "",
    "github.com/mayankgts/roster-sync"
) | ForEach-Object { Write-Host "  $_" -ForegroundColor Gray }
Wait-Secs 10