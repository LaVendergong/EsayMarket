# ============================================================================
# Shipping / favorite / report verification
#
# Covers:
#   - ship without a tracking number is rejected; with company+no it sticks
#   - buyer sees shipCompanyText + shipNo on the order detail
#   - favorite toggle is idempotent, list/check reflect the state, cancel clears it
#   - report summary/daily/top-products aggregate the new paid order
#
# Prerequisites: mysql migrated to sql/V21__favorite.sql, both apps running.
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File scripts\extras-verify.ps1
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
$outDir = Join-Path (Split-Path -Parent $PSScriptRoot) 'tmp\extras'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$reqFile = Join-Path $outDir '_request.json'
$respFile = Join-Path $outDir '_response.json'
$fails = 0
$tag = Get-Date -Format 'MMddHHmmss'
$skuId = 400          # iPhone 16 Pro 256G, 8999.00 (demo catalog)
$productId = 300

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

# ---------------------------------------------------------------- buyer + order
$user = "ex$tag"
$pwd = 'ex12345678'
Api POST "$Portal/portal/auth/register" @{ username = $user; password = $pwd; nickname = "Ex$tag" } $null | Out-Null
$token = (Api POST "$Portal/portal/auth/login" @{ username = $user; password = $pwd } $null).data.token
Step 'buyer login' ($null -ne $token) 'token ok'

$addrId = (Api POST "$Portal/portal/address" @{
    receiver = 'EX'; phone = '13600000000'; province = 'GD'; city = 'SZ'
    district = 'NS'; detail = 'Extra Road 1'; isDefault = 1
} $token).data

$order = Api POST "$Portal/portal/order/direct" @{
    skuId = $skuId; quantity = 1; addressId = $addrId; requestId = "ex-$tag"
} $token
$orderId = $order.data.orderId
$null = Api POST "$Portal/portal/order/$orderId/pay" @{} $token
Step 'order paid' ((Api GET "$Portal/portal/order/$orderId" $null $token).data.status -eq 1) 'status=1'

# ---------------------------------------------------------------- shipping
$noNo = Api POST "$Admin/admin/order/$orderId/ship" @{ company = 'SF'; shipNo = '' } $at
Step 'ship without tracking no is rejected' ($noNo.code -ne 200) "code=$($noNo.code)"

$badCompany = Api POST "$Admin/admin/order/$orderId/ship" @{ company = 'NOT_A_COMPANY'; shipNo = 'SF123456' } $at
Step 'unknown express company rejected' ($badCompany.code -ne 200) "code=$($badCompany.code)"

$tracking = "SF$tag"
$ship = Api POST "$Admin/admin/order/$orderId/ship" @{ company = 'SF'; shipNo = $tracking } $at
Step 'ship with company + no' ($ship.code -eq 200) "code=$($ship.code) no=$tracking"

$detail = Api GET "$Portal/portal/order/$orderId" $null $token
Step 'order becomes SHIPPED(2)' ($detail.data.status -eq 2) "status=$($detail.data.status)"
Step 'tracking visible to buyer' `
    (($detail.data.shipNo -eq $tracking) -and ($detail.data.shipCompanyText.Length -gt 0)) `
    "company=$($detail.data.shipCompanyText) no=$($detail.data.shipNo)"

$again = Api POST "$Admin/admin/order/$orderId/ship" @{ company = 'SF'; shipNo = "SF$tag-2" } $at
Step 'cannot ship twice' ($again.code -ne 200) "code=$($again.code)"

# ---------------------------------------------------------------- favorite
$fav = Api POST "$Portal/portal/favorite/$productId" $null $token
Step 'favorite product' (($fav.code -eq 200) -and ($fav.data -eq $true)) "data=$($fav.data)"

$check = Api GET "$Portal/portal/favorite/check/$productId" $null $token
Step 'check says favorited' ($check.data -eq $true) "data=$($check.data)"

$page = Api GET "$Portal/portal/favorite/page?pageNum=1&pageSize=10" $null $token
$hit = @($page.data.list | Where-Object { $_.productId -eq $productId }) | Select-Object -First 1
Step 'favorite list contains it' (($page.data.total -eq 1) -and ($null -ne $hit)) "total=$($page.data.total)"
Step 'favorite list carries product snapshot' (($hit.productName.Length -gt 0) -and ($hit.price -gt 0)) `
    "name=$($hit.productName) price=$($hit.price)"

$unfav = Api POST "$Portal/portal/favorite/$productId" $null $token
Step 'unfavorite product' (($unfav.code -eq 200) -and ($unfav.data -eq $false)) "data=$($unfav.data)"

$check2 = Api GET "$Portal/portal/favorite/check/$productId" $null $token
Step 'check says not favorited' ($check2.data -eq $false) "data=$($check2.data)"

$page2 = Api GET "$Portal/portal/favorite/page?pageNum=1&pageSize=10" $null $token
Step 'favorite list empty after cancel' ($page2.data.total -eq 0) "total=$($page2.data.total)"

# 反复收藏不会撞唯一键（逻辑删除后再收藏要复活，不能 insert）
$null = Api POST "$Portal/portal/favorite/$productId" $null $token
$favAgain = Api POST "$Portal/portal/favorite/$productId" $null $token
Step 're-favorite after cancel works' (($favAgain.code -eq 200) -and ($favAgain.data -eq $false)) `
    "code=$($favAgain.code) data=$($favAgain.data)"

# ---------------------------------------------------------------- report
$summary = Api GET "$Admin/admin/report/summary?days=7" $null $at
Step 'report summary has gmv' ($summary.data.gmv -ge 8999) "gmv=$($summary.data.gmv) orders=$($summary.data.orderCount)"
Step 'avg order amount computed' ($summary.data.avgOrderAmount -ge 0) "avg=$($summary.data.avgOrderAmount)"
Step 'net gmv = gmv - refund' `
    ([math]::Abs([double]$summary.data.netGmv - ([double]$summary.data.gmv - [double]$summary.data.refundAmount)) -lt 0.01) `
    "net=$($summary.data.netGmv) gmv=$($summary.data.gmv) refund=$($summary.data.refundAmount)"

$daily = Api GET "$Admin/admin/report/daily?days=7" $null $at
Step 'daily stats include today' (@($daily.data).Count -ge 1) "days=$(@($daily.data).Count)"

$top = Api GET "$Admin/admin/report/top-products?days=7&limit=10" $null $at
$topHit = @($top.data | Where-Object { $_.productId -eq $productId }) | Select-Object -First 1
Step 'top products include the sold item' ($null -ne $topHit) "count=$(@($top.data).Count)"
Step 'top product quantity aggregated' ($topHit.quantity -ge 1) "qty=$($topHit.quantity) amount=$($topHit.amount)"

Write-Host ''
Write-Host ("RESULT fails={0}" -f $fails)
if ($fails -gt 0) { exit 1 }
