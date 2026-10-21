# ============================================================================
# Cookie-based auth + CSRF verification.
#
# Why: the token used to live in localStorage, where ANY XSS payload can read it
# and keep using it offline for the whole 7-day lifetime. It now lives in an
# HttpOnly cookie, which JS cannot read. That change is only worth anything if
# the following actually hold, so each one is asserted here:
#
#   A) login sets an HttpOnly + SameSite cookie, and a JS-readable CSRF cookie
#   B) the cookie alone authenticates (no Authorization header)
#   C) state-changing requests authenticated by cookie are rejected without the
#      X-CSRF-Token header, and accepted with it
#   D) Bearer clients (scripts, server-to-server) are unaffected by CSRF
#   E) logout clears the cookie server-side and the old cookie stops working
#   F) the two apps use separate cookie names (a portal cookie cannot touch admin)
#
# ASCII-only on purpose (see docs/AI-HANDOFF.md section 8, item 28).
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File D:\projects\mall\scripts\auth-cookie-verify.ps1
# ============================================================================
param(
    [string]$MysqlPassword = '521131'
)

$ErrorActionPreference = 'Stop'
# PowerShell 5.1 需要显式加载 System.Net.Http
Add-Type -AssemblyName System.Net.Http
$root = Split-Path -Parent $PSScriptRoot
$portal = 'http://localhost:8090'
$admin = 'http://localhost:8081'
$mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$java = 'D:\jdks\jdk-17.0.12\bin\java.exe'
$outDir = Join-Path $root 'tmp\auth-cookie'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$reqFile = Join-Path $outDir '_request.json'
$script:results = New-Object System.Collections.Generic.List[object]
. (Join-Path $PSScriptRoot 'e2e-lib.ps1')

$STAMP = (Get-Date -Format 'HHmmss')
$BUYER = "cook$STAMP"
$PWD_OK = 'cookie123456'

function Sql([string]$q) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $mysql -uroot "-p$MysqlPassword" -N --default-character-set=utf8mb4 -e "USE mall; $q" 2>$null
        return ($out | Out-String).Trim()
    } finally { $ErrorActionPreference = $prev }
}

# ---------------------------------------------------------------- HTTP session
# 用 HttpClient + CookieContainer：Cookie 的自动携带、HttpOnly 解析都由 .NET 处理，
# 比自己拼 curl 参数可靠（也能直接读原始 Set-Cookie 头来断言标记）
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
    return [pscustomobject]@{
        status     = [int]$resp.StatusCode
        raw        = $text
        body       = $parsed
        setCookies = $setCookies
    }
}

function CookieList($session, [string]$url) { return @($session.jar.GetCookies([uri]$url)) }
function CookieValue($session, [string]$url, [string]$name) {
    $c = CookieList $session $url | Where-Object { $_.Name -eq $name }
    if ($c) { return $c[0].Value } else { return $null }
}

# --------------------------------------------------------------------------- setup
Write-Host '== setup =='
Stop-MallApps | Out-Null
if (-not (Start-MallApps 'authcookie')) {
    Check 'setup: both apps started' $false 'see logs'
    $fail = Save-Summary 'Cookie auth + CSRF verification'
    exit $fail
}
Check 'setup: both apps started' $true 'logs'

Sql "DELETE FROM t_user WHERE username = '$BUYER';" | Out-Null
$s = New-Session
$reg = Send $s 'POST' "$portal/api/v1/portal/auth/register" $null @{ username = $BUYER; password = $PWD_OK; nickname = 'C' }
Check 'setup: test user registered' ($reg.body.code -eq 200) "code=$($reg.body.code)"

# ============================================================ A) cookie flags
Write-Host ''
Write-Host '== A) login sets the right cookies =='
$login = Send $s 'POST' "$portal/api/v1/portal/auth/login" $null @{ username = $BUYER; password = $PWD_OK }
Check 'A0 login ok' ($login.body.code -eq 200) "code=$($login.body.code)"

$tokenSet = $login.setCookies | Where-Object { $_ -like 'mall_portal_token=*' }
$csrfSet = $login.setCookies | Where-Object { $_ -like 'mall_csrf=*' }
Check 'A1 token cookie is set on login' ([bool]$tokenSet) "count=$($login.setCookies.Count)"
Check 'A2 token cookie is HttpOnly (JS cannot read it)' ($tokenSet -match 'httponly') "raw=$($tokenSet)"
Check 'A3 token cookie is SameSite=Lax' ($tokenSet -match 'samesite=lax') "raw=$($tokenSet)"
Check 'A4 token cookie is NOT Secure in dev (http)' ($tokenSet -notmatch 'secure') "raw=$($tokenSet)"
Check 'A5 CSRF cookie is set and JS-readable (not HttpOnly)' ([bool]$csrfSet -and $csrfSet -notmatch 'httponly') "raw=$($csrfSet)"
Check 'A6 login response still returns a token for API clients' ([bool]$login.body.data.token) 'token in body (for scripts)'

# ============================================================ B) cookie authenticates
Write-Host ''
Write-Host '== B) the cookie alone authenticates =='
$meCookie = Send $s 'GET' "$portal/api/v1/portal/user/me" $null $null
Check 'B1 /user/me works with cookie only (no Authorization header)' ($meCookie.status -eq 200 -and $meCookie.body.code -eq 200) "http=$($meCookie.status) code=$($meCookie.body.code)"
Check 'B2 response carries no token/password' ($meCookie.raw -notmatch 'password' -and $meCookie.raw -notmatch '\$2[aby]\$' -and $meCookie.raw -notmatch 'eyJ') 'no secrets'

$anon = New-Session
$meAnon = Send $anon 'GET' "$portal/api/v1/portal/user/me" $null $null
Check 'B3 no credential at all -> 401' ($meAnon.status -eq 401) "http=$($meAnon.status)"

# ============================================================ C) CSRF
Write-Host ''
Write-Host '== C) CSRF double-submit (cookie auth only) =='
$addrBody = @{ receiver = 'Cookie Test'; phone = '13700002222'; province = 'GD'; city = 'SZ'; district = 'NS'; detail = 'CSRF Road 1'; isDefault = 1 }
$noCsrf = Send $s 'POST' "$portal/api/v1/portal/address" $null $addrBody
Check 'C1 cookie + POST without X-CSRF-Token -> 403' ($noCsrf.status -eq 403) "http=$($noCsrf.status) body=$($noCsrf.raw)"
Check 'C2 rejection message mentions CSRF' ($noCsrf.raw -match 'CSRF') "raw=$($noCsrf.raw)"

$csrfToken = CookieValue $s $portal 'mall_csrf'
Check 'C3 csrf cookie readable by the test client' ([bool]$csrfToken) "len=$(if ($csrfToken) { $csrfToken.Length } else { 0 })"

$withCsrf = Send $s 'POST' "$portal/api/v1/portal/address" @{ 'X-CSRF-Token' = $csrfToken } $addrBody
Check 'C4 cookie + POST with correct X-CSRF-Token -> accepted' ($withCsrf.status -eq 200 -and $withCsrf.body.code -eq 200) "http=$($withCsrf.status) code=$($withCsrf.body.code)"

$wrongCsrf = Send $s 'POST' "$portal/api/v1/portal/address" @{ 'X-CSRF-Token' = 'deadbeef' } $addrBody
Check 'C5 wrong X-CSRF-Token -> 403' ($wrongCsrf.status -eq 403) "http=$($wrongCsrf.status)"

# ============================================================ D) Bearer unaffected
Write-Host ''
Write-Host '== D) Bearer clients are not forced through CSRF =='
$bearer = New-Session
$bearerAddr = Send $bearer 'POST' "$portal/api/v1/portal/address" @{ Authorization = "Bearer $($login.body.data.token)" } $addrBody
Check 'D1 Bearer + POST without CSRF header -> accepted (scripts keep working)' ($bearerAddr.status -eq 200 -and $bearerAddr.body.code -eq 200) "http=$($bearerAddr.status) code=$($bearerAddr.body.code)"

# ============================================================ E) logout
Write-Host ''
Write-Host '== E) logout clears the cookie server-side =='
$out = Send $s 'POST' "$portal/api/v1/portal/auth/logout" $null $null
$clearedToken = $out.setCookies | Where-Object { $_ -like 'mall_portal_token=*' }
Check 'E1 logout returns clearing Set-Cookie for token' ([bool]$clearedToken -and $clearedToken -match 'max-age=0') "raw=$($clearedToken)"
Check 'E2 token cookie gone from the jar' ($null -eq (CookieValue $s $portal 'mall_portal_token')) 'jar cleared'
$afterLogout = Send $s 'GET' "$portal/api/v1/portal/user/me" $null $null
Check 'E3 old session no longer authenticates -> 401' ($afterLogout.status -eq 401) "http=$($afterLogout.status)"

# ============================================================ F) per-app cookies
Write-Host ''
Write-Host '== F) admin and portal use different cookie names =='
$sAdmin = New-Session
$adminLogin = Send $sAdmin 'POST' "$admin/api/v1/admin/auth/login" $null @{ username = 'malladmin'; password = 'admin123456' }
$adminSet = $adminLogin.setCookies | Where-Object { $_ -like 'mall_admin_token=*' }
Check 'F1 admin login sets mall_admin_token' ([bool]$adminSet -and $adminSet -match 'httponly') "code=$($adminLogin.body.code)"
$adminPage = Send $sAdmin 'GET' "$admin/api/v1/admin/order/page?pageNum=1&pageSize=1" $null $null
Check 'F2 admin cookie authenticates admin API' ($adminPage.status -eq 200 -and $adminPage.body.code -eq 200) "http=$($adminPage.status)"

$portalCookieOnAdmin = Send $s 'GET' "$admin/api/v1/admin/order/page?pageNum=1&pageSize=1" $null $null
Check 'F3 a portal-only session cannot touch the admin API' ($portalCookieOnAdmin.status -eq 401) "http=$($portalCookieOnAdmin.status)"

# ============================================================ cleanup
Write-Host ''
Write-Host '== cleanup =='
Sql ("DELETE FROM t_address WHERE user_id IN (SELECT id FROM t_user WHERE username = '$BUYER'); " +
    "DELETE FROM t_user WHERE username = '$BUYER';") | Out-Null
Check 'cleanup: test data removed' ([int](Sql "SELECT COUNT(*) FROM t_user WHERE username = '$BUYER';") -eq 0) 'leftover users = 0'

$fail = Save-Summary 'Cookie auth + CSRF verification'
exit $fail
