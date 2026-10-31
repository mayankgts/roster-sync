$Base = "http://localhost:8080"

function Get-Token($user, $pass) {
  $body = @{ username = $user; password = $pass } | ConvertTo-Json
  (Invoke-RestMethod -Method Post -Uri "$Base/api/v1/auth/token" -ContentType "application/json" -Body $body).accessToken
}

$HR    = Get-Token "hr-acme"    "hr-acme-pass"
$ADMIN = Get-Token "admin-acme" "admin-acme-pass"
$OPS   = Get-Token "ops-acme"   "ops-acme-pass"
$EMP   = Get-Token "E1001"      "e1001-pass"
Write-Host "Tokens loaded: `$HR `$ADMIN `$OPS `$EMP"