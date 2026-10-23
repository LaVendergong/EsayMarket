# ============================================================================
# Dashboard / hot keywords / refund consistency verification
#
# Covers:
#   - dashboard summary + low-stock list
#   - hot keywords ranked by search count
#   - refund rolls back product.sales, marks the review refunded, and drops it
#     from the product rating (but keeps it visible to buyers)
#
# Prerequisites: mysql migrated to sql/V22__search_log_and_review_refund.sql, both apps running.
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File scripts\ops-verify.ps1
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
$outDir = Join-Path (Split-Path -Parent $PSScriptRoot) 'tmp\ops'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$reqFile = Join-Path $outDir '_request.json'
$respFile = Join-Path $outDir '_response.json'
$fails = 0
$tag = Get-Date -Format 'MMddHHmmss'
$skuId = 400
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

# ---------------------------------------------------------------- dashboard
$sum = Api GET "$Admin/admin/dashboard/summary" $null $at
Step 'dashboard summary' (($sum.code -eq 200) -and ($null -ne $sum.data.todayOrders)) `
    "gmv=$($sum.data.todayGmv) orders=$($sum.data.todayOrders) low=$($sum.data.lowStockCount)"

$low = Api GET "$Admin/admin/dashboard/low-stock?limit=10" $null $at
Step 'low stock list is an array' ($low.code -eq 200 -and $null -ne $low.data) "count=$(@($low.data).Count)"
@($low.data) | Select-Object -First 3 | ForEach-Object { Write-Host "    - $($_.productName) avail=$($_.available)" }

# ---------------------------------------------------------------- hot keywords
$kw = "hot$tag"
foreach ($i in 1..3) { $null = Api GET "$Portal/portal/product/page?pageNum=1&pageSize=5&keyword=$kw" $null $null }
$null = Api GET "$Portal/portal/product/page?pageNum=1&pageSize=5&keyword=zz$tag" $null $null

$hot = Api GET "$Portal/portal/product/hot-keywords?limit=10" $null $null
Step 'hot keywords contains the most searched term' (@($hot.data) -contains $kw) ("count=" + @($hot.data).Count)

# ---------------------------------------------------------------- refund consistency
$user = "ops$tag"
$pwd = 'ops12345678'
Api POST "$Portal/portal/auth/register" @{ username = $user; password = $pwd; nickname = "Ops$tag" } $null | Out-Null
$token = (Api POST "$Portal/portal/auth/login" @{ username = $user; password = $pwd } $null).data.token

$salesBefore = [int](Api GET "$Portal/portal/product/$productId" $null $null).data.sales
$addr = (Api POST "$Portal/portal/address" @{
    receiver = 'OPS'; phone = '13500000000'; province = 'GD'; city = 'SZ'
    district = 'NS'; detail = 'Ops Road 1'; isDefault = 1
} $token).data

$orderId = (Api POST "$Portal/portal/order/direct" @{
    skuId = $skuId; quantity = 1; addressId = $addr; requestId = "ops-$tag"
} $token).data.orderId
$null = Api POST "$Portal/portal/order/$orderId/pay" @{} $token
$itemId = @((Api GET "$Portal/portal/order/$orderId" $null $token).data.items)[0].id
$salesAfterPay = [int](Api GET "$Portal/portal/product/$productId" $null $null).data.sales
Step 'sales increased on payment' ($salesAfterPay -eq $salesBefore + 1) "before=$salesBefore after=$salesAfterPay"

$null = Api POST "$Admin/admin/order/$orderId/ship" @{ company = 'SF'; shipNo = "SF$tag" } $at
$null = Api POST "$Portal/portal/order/$orderId/confirm" @{} $token
$rv = Api POST "$Portal/portal/review" @{ orderItemId = $itemId; rating = 5; content = 'ok then refunded' } $token
Step 'review submitted' ($rv.code -eq 200) "code=$($rv.code)"
$ratingBefore = (Api GET "$Portal/portal/product/$productId" $null $null).data.reviewCount
Step 'product has 1 review' ($ratingBefore -eq 1) "count=$ratingBefore"

$as = Api POST "$Portal/portal/after-sale" @{ orderId = $orderId; type = 1; reason = 'refund test' } $token
$asId = $as.data
Step 'after-sale applied' ($as.code -eq 200 -and $asId) "id=$asId code=$($as.code)"

$approve = Api POST "$Admin/admin/after-sale/$asId/approve" @{ remark = 'ok' } $at
Step 'after-sale approved' ($approve.code -eq 200) "code=$($approve.code)"

$orderAfter = Api GET "$Portal/portal/order/$orderId" $null $token
Step 'order becomes REFUNDED(6)' ($orderAfter.data.status -eq 6) "status=$($orderAfter.data.status)"

$salesAfterRefund = [int](Api GET "$Portal/portal/product/$productId" $null $null).data.sales
Step 'sales rolled back after refund' ($salesAfterRefund -eq $salesBefore) `
    "before=$salesBefore afterRefund=$salesAfterRefund"

$detail = Api GET "$Portal/portal/product/$productId" $null $null
Step 'refunded review no longer counts in rating' ($detail.data.reviewCount -eq 0) "count=$($detail.data.reviewCount)"

$list = Api GET "$Portal/portal/review/product/${productId}?pageNum=1&pageSize=20" $null $null
$row = @($list.data.list | Where-Object { $_.orderItemId -eq $itemId }) | Select-Object -First 1
Step 'review still visible with refunded flag' (($null -ne $row) -and ($row.refunded -eq 1)) `
    "refunded=$($row.refunded)"

$adminReviews = Api GET "$Admin/admin/review/page?pageNum=1&pageSize=20&productId=$productId" $null $at
$adminRow = @($adminReviews.data.list | Where-Object { $_.orderItemId -eq $itemId }) | Select-Object -First 1
Step 'admin sees refunded marker' (($null -ne $adminRow) -and ($adminRow.refunded -eq 1)) `
    "refunded=$($adminRow.refunded)"

Write-Host ''
Write-Host ("RESULT fails={0}" -f $fails)
if ($fails -gt 0) { exit 1 }
