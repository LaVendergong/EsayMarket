# ============================================================================
# Access / refresh token lifecycle verification.
#
# Why: the old design issued a 7-day access token that could NOT be revoked.
# Logging out only removed the browser cookie -- a leaked token stayed valid for
# a week. The new design is "short access + revocable refresh", and this script
# proves each half actually works, against a REAL running system:
#
#   A) login issues a SHORT access token (5s here) + a long refresh cookie
#   B) the access token really expires (401), not just "should"
#   C) refresh exchanges it for a new access token, and the OLD refresh is
#      burned (rotation) -- replaying it must fail
#   D) logout revokes the refresh in the database; refresh afterwards fails
#   E) Bearer clients (scripts / server-to-server) keep working untouched
#
# It runs the apps with MALL_JWT_ACCESS_EXPIRE_MILLIS=5000 so expiry is testable
# in seconds instead of minutes, then restores the default settings.
#
# ASCII-only on purpose (see docs/AI-HANDOFF.md section 8, item 28).
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File D:\projects\mall\scripts\token-lifecycle-verify.ps1
# ============================================================================
param(
    [string]$MysqlPassword = '521131',
    [int]$AccessMs = 5000
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
$root = Split-Path -Parent $PSScriptRoot
$portal = 'http://localhost:8090'
$admin = 'http://localhost:8081'
$mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$java = 'D:\jdks\jdk-17.0.12\bin\java.exe'
$outDir = Join-Path $root 'tmp\token-lifecycle'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$reqFile = Join-Path $outDir '_request.json'
$script:results = New-Object System.Collections.Generic.List[object]
. (Join-Path $PSScriptRoot 'e2e-lib.ps1')

$STAMP = (Get-Date -Format 'HHmmss')
$BUYER = "tok$STAMP"
$PWD_OK = 'token123456'

function Sql([string]$q) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $mysql -uroot "-p$MysqlPassword" -N --default-character-set=utf8mb4 -e "USE mall; $q" 2>$null
        return ($out | Out-String).Trim()
    } finally { $ErrorActionPreference = $prev }
}

function New-Session {
    $handler = New-Object System.Net.Http.HttpClientHandler
    $handler.CookieContainer = New-Object System.Net.CookieContainer
    $handler.UseCookies = $true
    $client = New-Object System.Net.Http.HttpClient($handler)
    $client.Timeout = [TimeSpan]::FromSeconds(20)
    return [pscustomobject]@{ client = $client; handler = $handler; jar = $handler.CookieContainer }
}

function Send($session, [string]$method, [string]$url, $headers, $body) {
    $req = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($method), $url)
    if ($headers) { foreach ($k in $headers.Keys) { [void]$req.Headers.TryAddWithoutValidation($k, [string]$headers[$k]) } }
    if ($null -ne $body) {
        $json = if ($body -is [string]) { $body } else { $body | ConvertTo-Json -Depth 8 -Compress }
        $req.Content = New-Object System.Net.Http.StringContent($json, [System.Text.Encoding]::UTF8, 'application/json')
    }
    $resp = $session.client.SendAsync($req).GetAwaiter().GetResult()
    $text = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    $setCookies = @()
    $values = $null
    if ($resp.Headers.TryGetValues('Set-Cookie', [ref]$values)) { $setCookies = @($values) }
    $parsed = $null
    if ($text) { try { $parsed = $text | ConvertFrom-Json } catch { } }
    return [pscustomobject]@{ status = [int]$resp.StatusCode; raw = $text; body = $parsed; setCookies = $setCookies }
}

function CookieValue($session, [string]$url, [string]$name) {
    $c = @($session.jar.GetCookies([uri]$url)) | Where-Object { $_.Name -eq $name }
    if ($c) { return $c[0].Value } else { return $null }
}
function SetCookieValue($session, [string]$url, [string]$name, [string]$value) {
    $session.jar.Add([uri]$url, [System.Net.Cookie]::new($name, $value))
}

# 解析 JWT payload（不验签，只看 exp / iat）
function JwtClaims([string]$token) {
    $parts = $token.Split('.')
    if ($parts.Length -lt 2) { return $null }
    $p = $parts[1].Replace('-', '+').Replace('_', '/')
    switch ($p.Length % 4) { 2 { $p += '==' } 3 { $p += '=' } }
    try { return ([System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($p)) | ConvertFrom-Json) } catch { return $null }
}

function MaxAgeOf($setCookies, [string]$name) {
    $line = $setCookies | Where-Object { $_ -like "$name=*" } | Select-Object -First 1
    if (-not $line) { return -1 }
    $m = [regex]::Match($line, 'Max-Age=(\d+)')
    if ($m.Success) { return [int]$m.Groups[1].Value }
    return -1
}

# --------------------------------------------------------------------------- run with a 5s access TTL
Write-Host "== setup: start apps with a ${AccessMs}ms access TTL =="
Stop-MallApps | Out-Null
$env:MALL_JWT_ACCESS_EXPIRE_MILLIS = [string]$AccessMs
if (-not (Start-MallApps 'tokenlifecycle')) {
    Remove-Item Env:\MALL_JWT_ACCESS_EXPIRE_MILLIS -ErrorAction SilentlyContinue
    Check 'setup: both apps started' $false 'see logs'
    $fail = Save-Summary 'Token lifecycle verification'
    exit $fail
}
Remove-Item Env:\MALL_JWT_ACCESS_EXPIRE_MILLIS -ErrorAction SilentlyContinue
Check 'setup: both apps started with the short access TTL' $true "accessMs=$AccessMs"

Sql ("DELETE FROM t_refresh_token WHERE user_id IN (SELECT id FROM t_user WHERE username = '$BUYER'); " +
    "DELETE FROM t_user WHERE username = '$BUYER';") | Out-Null
$s = New-Session
$reg = Send $s 'POST' "$portal/api/v1/portal/auth/register" $null @{ username = $BUYER; password = $PWD_OK; nickname = 'T' }
Check 'setup: test user registered' ($reg.body.code -eq 200) "code=$($reg.body.code)"

# ============================================================ A) what login issues
Write-Host ''
Write-Host '== A) login issues a short access + a long refresh =='
$login = Send $s 'POST' "$portal/api/v1/portal/auth/login" $null @{ username = $BUYER; password = $PWD_OK }
$accessToken = $login.body.data.token
Check 'A1 login ok' ($login.body.code -eq 200 -and [bool]$accessToken) "code=$($login.body.code)"

$claims = JwtClaims $accessToken
$ttl = if ($claims) { [int]$claims.exp - [int]$claims.iat } else { -1 }
Check "A2 access JWT lifetime is ~${AccessMs}ms (claims exp-iat)" ($ttl -eq [int]($AccessMs / 1000)) "exp-iat=${ttl}s"

$accessMaxAge = MaxAgeOf $login.setCookies 'mall_portal_token'
$refreshMaxAge = MaxAgeOf $login.setCookies 'mall_portal_refresh'
Check 'A3 access cookie carries the short Max-Age' ($accessMaxAge -eq [int]($AccessMs / 1000)) "maxAge=$accessMaxAge"
Check 'A4 refresh cookie lives much longer (7 days)' ($refreshMaxAge -ge 604800) "maxAge=$refreshMaxAge"
Check 'A5 refresh cookie is HttpOnly' (($login.setCookies | Where-Object { $_ -like 'mall_portal_refresh=*' }) -match 'httponly') 'HttpOnly'

$me1 = Send $s 'GET' "$portal/api/v1/portal/user/me" $null $null
Check 'A6 access works right after login' ($me1.status -eq 200) "http=$($me1.status)"

# ============================================================ B) access really expires
Write-Host ''
Write-Host "== B) wait for the access token to expire (${AccessMs}ms) =="
Start-Sleep -Milliseconds ($AccessMs + 1500)
$me2 = Send $s 'GET' "$portal/api/v1/portal/user/me" $null $null
Check 'B1 expired access is rejected with 401' ($me2.status -eq 401) "http=$($me2.status)"
Check 'B2 the refresh cookie is still there (expiry is per-token, not per-session)' ([bool](CookieValue $s $portal 'mall_portal_refresh')) 'refresh present'

# ============================================================ C) refresh rotates
Write-Host ''
Write-Host '== C) refresh exchanges it for a new access, and burns the old refresh =='
$oldRefresh = CookieValue $s $portal 'mall_portal_refresh'
$refreshResp = Send $s 'POST' "$portal/api/v1/portal/auth/refresh" $null $null
Check 'C1 refresh call succeeds' ($refreshResp.status -eq 200 -and $refreshResp.body.code -eq 200) "http=$($refreshResp.status) code=$($refreshResp.body.code)"
$newRefresh = CookieValue $s $portal 'mall_portal_refresh'
Check 'C2 a NEW refresh token was issued' ([bool]$newRefresh -and $newRefresh -ne $oldRefresh) "rotated=$([bool]$newRefresh -and $newRefresh -ne $oldRefresh)"

$me3 = Send $s 'GET' "$portal/api/v1/portal/user/me" $null $null
Check 'C3 the new access token works again' ($me3.status -eq 200) "http=$($me3.status)"

# 用旧 refresh 重放：必须失败（轮换的意义）
$replaySession = New-Session
SetCookieValue $replaySession $portal 'mall_portal_refresh' $oldRefresh
$replay = Send $replaySession 'POST' "$portal/api/v1/portal/auth/refresh" $null $null
Check 'C4 replaying the OLD refresh fails (one-time use)' ($replay.body.code -eq 401 -or $replay.status -eq 401) "http=$($replay.status) code=$($replay.body.code)"

# ============================================================ D) logout revokes
Write-Host ''
Write-Host '== D) logout revokes the refresh row in the database =='
$currentRefresh = CookieValue $s $portal 'mall_portal_refresh'
$out = Send $s 'POST' "$portal/api/v1/portal/auth/logout" $null $null
Check 'D1 logout ok' ($out.body.code -eq 200) "code=$($out.body.code)"
Check 'D2 logout clears the access cookie' ((MaxAgeOf $out.setCookies 'mall_portal_token') -eq 0) "maxAge=$(MaxAgeOf $out.setCookies 'mall_portal_token')"

$userId = [long](Sql "SELECT id FROM t_user WHERE username = '$BUYER';")
$activeRows = [int](Sql "SELECT COUNT(*) FROM t_refresh_token WHERE user_id = $userId AND revoked = 0;")
$allRows = [int](Sql "SELECT COUNT(*) FROM t_refresh_token WHERE user_id = $userId;")
Check 'D3 every refresh row for this user is revoked in the DB' ($activeRows -eq 0 -and $allRows -gt 0) "rows=$allRows active=$activeRows"

$afterLogout = New-Session
SetCookieValue $afterLogout $portal 'mall_portal_refresh' $currentRefresh
$refreshAfterLogout = Send $afterLogout 'POST' "$portal/api/v1/portal/auth/refresh" $null $null
Check 'D4 refresh after logout is rejected' ($refreshAfterLogout.body.code -eq 401 -or $refreshAfterLogout.status -eq 401) "http=$($refreshAfterLogout.status) code=$($refreshAfterLogout.body.code)"

# ============================================================ E) Bearer unaffected
Write-Host ''
Write-Host '== E) Bearer clients are unaffected =='
$bearerSession = New-Session
$loginB = Send $bearerSession 'POST' "$portal/api/v1/portal/auth/login" $null @{ username = $BUYER; password = $PWD_OK }
$bearerMe = Send $bearerSession 'GET' "$portal/api/v1/portal/user/me" @{ Authorization = "Bearer $($loginB.body.data.token)" } $null
Check 'E1 Bearer-only request works (scripts unchanged)' ($bearerMe.status -eq 200) "http=$($bearerMe.status)"

# ============================================================ restore
Write-Host ''
Write-Host '== restore: restart apps with default TTLs =='
Stop-MallApps | Out-Null
$ok = Start-MallApps 'restore'
Check 'F1 apps restarted with default settings' $ok 'logs'
$s2 = New-Session
$loginD = Send $s2 'POST' "$portal/api/v1/portal/auth/login" $null @{ username = $BUYER; password = $PWD_OK }
$claimsD = JwtClaims $loginD.body.data.token
$ttlD = if ($claimsD) { [int]$claimsD.exp - [int]$claimsD.iat } else { -1 }
Check 'F2 default access TTL is 15 minutes again' ($ttlD -eq 900) "exp-iat=${ttlD}s"

Sql ("DELETE FROM t_refresh_token WHERE user_id = $userId; " +
    "DELETE FROM t_user WHERE username = '$BUYER';") | Out-Null
Check 'cleanup: test data removed' ([int](Sql "SELECT COUNT(*) FROM t_user WHERE username = '$BUYER';") -eq 0) 'leftover users = 0'

$fail = Save-Summary 'Token lifecycle verification (access expiry + refresh rotation + revocation)'
exit $fail
