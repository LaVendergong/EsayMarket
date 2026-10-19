# ============================================================================
# Production-hardening checks: traceId / CORS whitelist / login throttling.
#
#   A) traceId    - response carries X-Trace-Id; upstream value is honoured;
#                   the value really lands in the log; suspicious input is replaced
#   B) CORS       - a whitelisted origin gets Access-Control-Allow-Origin;
#                   a foreign origin gets none (credentials are enabled, so
#                   a wildcard would be a real hole)
#   C) throttling - after maxFailures wrong passwords, even the CORRECT password
#                   is refused (throttle wins over correctness); a success resets
#                   the counter; other usernames are unaffected
#
# ASCII-only on purpose (see docs/AI-HANDOFF.md section 8, item 28).
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File D:\projects\mall\scripts\security-verify.ps1
#        (it starts/stops the two backends itself)
# ============================================================================
param(
    [string]$MysqlPassword = '521131',
    [int]$MaxFailures = 5,
    [string]$AllowedOrigin = 'http://localhost:5173'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$portal = 'http://localhost:8090'
$admin = 'http://localhost:8081'
$mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$java = 'D:\jdks\jdk-17.0.12\bin\java.exe'
$outDir = Join-Path $root 'tmp\security'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$reqFile = Join-Path $outDir '_request.json'
$script:results = New-Object System.Collections.Generic.List[object]
. (Join-Path $PSScriptRoot 'e2e-lib.ps1')

$suffix = Get-Date -Format 'HHmmss'
$USER_A = "sec-a-$suffix"
$USER_B = "sec-b-$suffix"
$PWD_GOOD = 'secret123456'
$PWD_BAD = 'wrong-password'

function Sql([string]$q) { return (Invoke-Mysql "USE mall; $q").Trim() }

function Purge {
    Sql "DELETE FROM t_user WHERE username LIKE 'sec-%';" | Out-Null
}

# 需要看响应头，e2e-lib 的 Http-Json 只回 body，这里直接用 curl -D
function Http-Headers([string]$method, [string]$url, [string[]]$headers) {
    $hf = Join-Path $outDir 'headers.txt'
    $bf = Join-Path $outDir 'body.txt'
    Remove-Item $hf, $bf -Force -ErrorAction SilentlyContinue
    $cargs = @('-s', '-S', '-X', $method, $url, '-D', $hf, '-o', $bf, '-w', '%{http_code}')
    foreach ($h in $headers) { $cargs += @('-H', $h) }
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $code = (& curl.exe @cargs 2>$null | Out-String).Trim() } finally { $ErrorActionPreference = $prev }
    $raw = Read-Text $hf
    $map = @{}
    foreach ($line in ($raw -split "`r?`n")) {
        if ($line -match '^([A-Za-z0-9-]+):\s*(.*)$') { $map[$matches[1].ToLower()] = $matches[2].Trim() }
    }
    return [pscustomobject]@{ http = [int]$code; headers = $map; body = (Read-Text $bf) }
}

function Login([string]$username, [string]$password, [string]$name, [string]$traceId) {
    $h = @()
    if ($traceId) { $h += "X-Trace-Id: $traceId" }
    return Http-Headers 'POST' "$portal/api/v1/portal/auth/login" ($h + @('Content-Type: application/json'))
}

# --------------------------------------------------------------------------- setup
Write-Host '== setup: start both apps =='
Stop-MallApps | Out-Null
if (-not (Start-MallApps 'security')) {
    Check 'setup: both apps started' $false 'see logs'
    $fail = Save-Summary 'Production hardening verification'
    exit $fail
}
Check 'setup: both apps started' $true 'logs\admin.log + logs\portal.log'
Purge

# 两个专用账号（用户名带时间戳，避免上一轮的限流记录残留影响本次）
Http-Json -Method POST -Url "$portal/api/v1/portal/auth/register" -Body @{ username = $USER_A; password = $PWD_GOOD; nickname = 'Sec A' } -Name '01-reg-a' | Out-Null
Http-Json -Method POST -Url "$portal/api/v1/portal/auth/register" -Body @{ username = $USER_B; password = $PWD_GOOD; nickname = 'Sec B' } -Name '02-reg-b' | Out-Null
$created = [int](Sql "SELECT COUNT(*) FROM t_user WHERE username IN ('$USER_A','$USER_B');")
Check 'setup: two throwaway accounts created' ($created -eq 2) "count=$created"

# ============================================================ A) traceId
Write-Host ''
Write-Host '== A) traceId =='
$r = Http-Headers 'GET' "$portal/api/v1/portal/product/page?pageNum=1&pageSize=1" @()
$trace = $r.headers['x-trace-id']
Check 'A1 response carries X-Trace-Id (16 hex)' ($trace -match '^[0-9a-f]{16}$') "trace=$trace"

$fixed = "secverify$suffix"
$r2 = Http-Headers 'GET' "$portal/api/v1/portal/product/page?pageNum=1&pageSize=1" @("X-Trace-Id: $fixed")
Check 'A2 upstream X-Trace-Id is honoured' ($r2.headers['x-trace-id'] -eq $fixed) "echoed=$($r2.headers['x-trace-id'])"

# 触发一条 WARN 日志（密码错误），日志行里必须带这个 traceId
$fixed2 = "seclog$suffix"
Http-Headers 'POST' "$portal/api/v1/portal/auth/login" @("X-Trace-Id: $fixed2", 'Content-Type: application/json') | Out-Null
Start-Sleep -Milliseconds 800
$logText = Read-Text (Join-Path $root 'logs\portal.log')
Check 'A3 traceId really lands in the log line' ($logText -like "*$fixed2*") "log contains $fixed2 = $($logText -like "*$fixed2*")"

# 不可信输入要被清洗掉。这里用「超长值」来验：curl 无法在 header 里发送真实换行
#（换行注入那条路径由 TraceIdFilterTest#suspiciousUpstreamValue_isReplacedNotEchoed 覆盖）
$overlong = 'y' * 65
$r3 = Http-Headers 'GET' "$portal/api/v1/portal/product/page?pageNum=1&pageSize=1" @("X-Trace-Id: $overlong")
Check 'A4 over-long upstream value is replaced, not echoed' (
    $r3.headers['x-trace-id'] -match '^[0-9a-f]{16}$'
) "trace=$($r3.headers['x-trace-id'])"

# ============================================================ B) CORS whitelist
Write-Host ''
Write-Host '== B) CORS whitelist =='
$ok = Http-Headers 'GET' "$portal/api/v1/portal/product/page?pageNum=1&pageSize=1" @("Origin: $AllowedOrigin")
Check "B1 whitelisted origin gets Allow-Origin" ($ok.headers['access-control-allow-origin'] -eq $AllowedOrigin) "allow=$($ok.headers['access-control-allow-origin'])"

$bad = Http-Headers 'GET' "$portal/api/v1/portal/product/page?pageNum=1&pageSize=1" @('Origin: http://evil.example')
Check 'B2 foreign origin gets NO Allow-Origin' (-not $bad.headers.ContainsKey('access-control-allow-origin')) "http=$($bad.http) allow=$($bad.headers['access-control-allow-origin'])"

# ============================================================ C) login throttling
Write-Host ''
Write-Host '== C) login throttling (maxFailures=' + $MaxFailures + ') =='
for ($i = 1; $i -le $MaxFailures; $i++) {
    $lastWrong = Http-Json -Method POST -Url "$portal/api/v1/portal/auth/login" -Body @{ username = $USER_A; password = $PWD_BAD } -Name "10-wrong-$i"
}
Check "C1 first $MaxFailures wrong passwords return 1003" ((Code $lastWrong) -eq 1003) "lastCode=$(Code $lastWrong)"

# 关键：锁定后即使给**正确**密码也必须拒绝（限流优先于正确性）
$blocked = Http-Json -Method POST -Url "$portal/api/v1/portal/auth/login" -Body @{ username = $USER_A; password = $PWD_GOOD } -Name '11-locked'
Check 'C2 CORRECT password is refused while locked (1005)' ((Code $blocked) -eq 1005) "code=$(Code $blocked) msg=$($blocked.body.message)"
$lockedRows = [int](Sql "SELECT COUNT(*) FROM t_user WHERE username = '$USER_A';")
Check 'C3 the account itself is untouched (not deleted/disabled)' ($lockedRows -eq 1) "rows=$lockedRows"

# 另一个用户名不受影响
$other = Http-Json -Method POST -Url "$portal/api/v1/portal/auth/login" -Body @{ username = $USER_B; password = $PWD_GOOD } -Name '12-other-ok'
Check 'C4 other usernames are unaffected' ((Code $other) -eq 200 -and (Payload $other).token) "code=$(Code $other)"

# 成功登录会清零：B 再来 2 次失败后仍然能登进去
Http-Json -Method POST -Url "$portal/api/v1/portal/auth/login" -Body @{ username = $USER_B; password = $PWD_BAD } -Name '13-b-bad1' | Out-Null
Http-Json -Method POST -Url "$portal/api/v1/portal/auth/login" -Body @{ username = $USER_B; password = $PWD_BAD } -Name '13-b-bad2' | Out-Null
$afterTwo = Http-Json -Method POST -Url "$portal/api/v1/portal/auth/login" -Body @{ username = $USER_B; password = $PWD_GOOD } -Name '14-b-ok'
Check 'C5 two failures then success still works (below limit)' ((Code $afterTwo) -eq 200) "code=$(Code $afterTwo)"

# 正常登录没有被这次加固弄坏
$normal = Http-Json -Method POST -Url "$admin/api/v1/admin/auth/login" -Body @{ username = 'malladmin'; password = 'admin123456' } -Name '15-admin-ok'
Check 'C6 admin login still works' ((Code $normal) -eq 200 -and (Payload $normal).role -eq 1) "code=$(Code $normal)"

# ============================================================ cleanup
Write-Host ''
Write-Host '== cleanup =='
Purge
Check 'cleanup: throwaway accounts removed' ([int](Sql "SELECT COUNT(*) FROM t_user WHERE username LIKE 'sec-%';") -eq 0) 'leftover users = 0'

$fail = Save-Summary 'Production hardening verification (traceId / CORS / login throttle)'
exit $fail
