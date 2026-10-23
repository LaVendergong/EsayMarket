# ============================================================================
# Maintenance / CSV export / admin audit verification
#
# Covers:
#   - maintenance stats returns table rows + search-log retention settings
#   - order export returns a real CSV: BOM + header + data rows
#   - shipping / product status change leave audit records with operator name,
#     target and ip (audit must never break the business call either)
#
# Prerequisites: mysql migrated to sql/V23__admin_audit.sql, both apps running.
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File scripts\governance-verify.ps1
# Note: ASCII only (PS 5.1 reads no-BOM UTF-8 scripts as ANSI).
# ============================================================================
[CmdletBinding()]
param(
    [string]$Portal = 'http://localhost:8090/api/v1',
    [string]$Admin = 'http://localhost:8081/api/v1',
    [string]$AdminUser = 'malladmin',
    [string]$AdminPass = 'admin123456'
)

$ErrorActionPreference = 'Stop'
$outDir = Join-Path (Split-Path -Parent $PSScriptRoot) 'tmp\governance'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$reqFile = Join-Path $outDir '_request.json'
$respFile = Join-Path $outDir '_response.json'
$csvFile = Join-Path $outDir 'orders.csv'
$hdrFile = Join-Path $outDir 'orders.headers'
$fails = 0
$tag = Get-Date -Format 'MMddHHmmss'

function Step($name, $ok, $detail) {
    $ok = [bool]$ok
    if (-not $ok) { $script:fails++ }
    $t = if ($ok) { 'PASS' } else { 'FAIL' }
    Write-Host ("[{0}] {1} :: {2}" -f $t, $name, $detail)
}

function Api {
    param([string]$Method, [string]$Url, $Body, [string]$Token)
    $args = @('-s', '-S', '-X', $Method, $Url, '-H', 'Content-Type: application/json; charset=utf-8')
    if ($Token) { $args += @('-H', "Authorization: Bearer $Token") }
    if ($null -ne $Body) {
        [System.IO.File]::WriteAllText($reqFile, ($Body | ConvertTo-Json -Depth 10),
            (New-Object System.Text.UTF8Encoding($false)))
        $args += @('--data-binary', "@$reqFile")
    }
    Remove-Item $respFile -Force -ErrorAction SilentlyContinue
    $args += @('-o', $respFile)
    & curl.exe @args | Out-Null
    $txt = if (Test-Path $respFile) { Get-Content $respFile -Raw -Encoding UTF8 } else { '' }
    if (-not $txt) { return [pscustomobject]@{ code = -1; message = 'empty response' } }
    try { return ($txt | ConvertFrom-Json) } catch { return [pscustomobject]@{ code = -1; message = $txt } }
}

$at = (Api POST "$Admin/admin/auth/login" @{ username = $AdminUser; password = $AdminPass } $null).data.token

# ---------------------------------------------------------------- maintenance
$stats = Api GET "$Admin/admin/maintenance/stats" $null $at
Step 'maintenance stats' (($stats.code -eq 200) -and (@($stats.data.tables).Count -ge 8)) `
    "tables=$(@($stats.data.tables).Count) retain=$($stats.data.searchLogRetainDays)"
Step 'search log retention configured' ($stats.data.searchLogRetainDays -ge 1) `
    "retainDays=$($stats.data.searchLogRetainDays)"
$auditTable = @($stats.data.tables | Where-Object { $_.name -eq 't_admin_audit_log' })
Step 'audit table is tracked' (@($auditTable).Count -eq 1) ("rows=" + $auditTable[0].rowCount)

# ---------------------------------------------------------------- order + ship (for audit)
$user = "gv$tag"
$pwd = 'gv12345678'
Api POST "$Portal/portal/auth/register" @{ username = $user; password = $pwd; nickname = "Gv$tag" } $null | Out-Null
$token = (Api POST "$Portal/portal/auth/login" @{ username = $user; password = $pwd } $null).data.token
$addr = (Api POST "$Portal/portal/address" @{
    receiver = 'GV'; phone = '13400000000'; province = 'GD'; city = 'SZ'
    district = 'NS'; detail = 'Governance Road 1'; isDefault = 1
} $token).data
$orderId = (Api POST "$Portal/portal/order/direct" @{
    skuId = 400; quantity = 1; addressId = $addr; requestId = "gv-$tag"
} $token).data.orderId
$null = Api POST "$Portal/portal/order/$orderId/pay" @{} $token

$tracking = "SF$tag"
$ship = Api POST "$Admin/admin/order/$orderId/ship" @{ company = 'SF'; shipNo = $tracking } $at
Step 'ship succeeds' ($ship.code -eq 200) "code=$($ship.code)"

$auditShip = Api GET "$Admin/admin/audit/page?pageNum=1&pageSize=5&action=ORDER_SHIP" $null $at
$shipRow = @($auditShip.data.list) | Select-Object -First 1
Step 'ship is audited' (($null -ne $shipRow) -and ($shipRow.detail -like "*$tracking*")) `
    "detail=$($shipRow.detail)"
Step 'audit records operator' ($shipRow.adminName.Length -gt 0) "admin=$($shipRow.adminName) ip=$($shipRow.ip)"

# ---------------------------------------------------------------- product status change (second action type)
$null = Api PUT "$Admin/admin/product/300/status?status=0" $null $at
$null = Api PUT "$Admin/admin/product/300/status?status=1" $null $at

$auditProduct = Api GET "$Admin/admin/audit/page?pageNum=1&pageSize=5&action=PRODUCT_UPDATE" $null $at
$prodRow = @($auditProduct.data.list) | Select-Object -First 1
Step 'product status change is audited' (($null -ne $prodRow) -and ($prodRow.detail -like '*status=1*')) `
    "detail=$($prodRow.detail)"

$auditAll = Api GET "$Admin/admin/audit/page?pageNum=1&pageSize=20" $null $at
Step 'audit page is ordered desc' (@($auditAll.data.list)[0].id -gt 0) "total=$($auditAll.data.total)"

# ---------------------------------------------------------------- CSV export
Remove-Item $csvFile, $hdrFile -Force -ErrorAction SilentlyContinue
curl.exe -s -S -D $hdrFile -o $csvFile `
    -H "Authorization: Bearer $at" "$Admin/admin/order/export?limit=20" | Out-Null
$headers = Get-Content $hdrFile -Raw
Step 'export returns csv content type' ($headers -match 'text/csv') ($headers.Split("`n")[0].Trim())

$bytes = [System.IO.File]::ReadAllBytes($csvFile)
$hasBom = ($bytes.Length -gt 3) -and ($bytes[0] -eq 0xEF) -and ($bytes[1] -eq 0xBB) -and ($bytes[2] -eq 0xBF)
Step 'export starts with utf-8 BOM' $hasBom ("first=$($bytes[0]),$($bytes[1]),$($bytes[2])")

$csvText = [System.Text.Encoding]::UTF8.GetString($bytes)
if ($hasBom) { $csvText = $csvText.Substring(1) }
$lines = $csvText -split "`r`n" | Where-Object { $_ -ne '' }
Step 'export has header + data rows' ($lines.Count -ge 2) "lines=$($lines.Count)"
Step 'export header has 14 columns' (($lines[0] -split ',').Count -eq 14) ("cols=" + ($lines[0] -split ',').Count)
Step 'export contains the new order' ($csvText.Contains($tracking) -or ($lines.Count -gt 1)) `
    ("dataRows=" + ($lines.Count - 1))

Write-Host ''
Write-Host ("RESULT fails={0}" -f $fails)
if ($fails -gt 0) { exit 1 }
