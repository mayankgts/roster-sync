$Base = "http://localhost:8080"

function Get-Token($user, $pass) {
  $body = @{ username = $user; password = $pass } | ConvertTo-Json
  (Invoke-RestMethod -Method Post -Uri "$Base/api/v1/auth/token" -ContentType "application/json" -Body $body).accessToken
}

# Call METHOD PATH [-Token t] [-File path]
function Call($Method, $Path, $Token, $File) {
  $params = @{ Method = $Method; Uri = "$Base$Path"; ContentType = "application/json"; Headers = @{} }
  if ($Token) { $params.Headers.Authorization = "Bearer $Token" }
  if ($File)  { $params.InFile = $File }
  try {
    $result = Invoke-RestMethod @params
    Write-Host "HTTP 200" -ForegroundColor Green
    $result | ConvertTo-Json -Depth 8
  } catch {
    $code = $_.Exception.Response.StatusCode.value__
    Write-Host "HTTP $code" -ForegroundColor Yellow
    if ($_.ErrorDetails.Message) { $_.ErrorDetails.Message }
  }
}

$HR    = Get-Token "hr-acme"    "hr-acme-pass"
$ADMIN = Get-Token "admin-acme" "admin-acme-pass"
$OPS   = Get-Token "ops-acme"   "ops-acme-pass"
$EMP   = Get-Token "E1001"      "e1001-pass"
Write-Host "Tokens loaded: `$HR `$ADMIN `$OPS `$EMP" -ForegroundColor Cyan