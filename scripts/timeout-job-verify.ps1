# ============================================================================
# Timeout-job verification: the "close expired unpaid orders" job must not be a
# single point of failure, and must close each order exactly once.
#
# Background: the job used to be enabled only in mall-admin, so if admin was down
# (or never started) expired orders were never closed and their locked stock was
# never released. It is now enabled in BOTH apps, which is safe because closing
# uses a conditional UPDATE on the from-status: only the instance that actually
# moved status 0 -> 4 releases the stock.
#
# What it proves:
#   A) mall-portal ALONE closes an expired order (admin stopped) -- the headline fix
#   B) with BOTH apps scanning, the order is still closed exactly once
#      (asserted by summing the "[timeout-job] closed N" counts from both logs)
#
# ASCII-only on purpose (see docs/AI-HANDOFF.md section 8, item 28).
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File D:\projects\mall\scripts\timeout-job-verify.ps1
#        (it stops/starts the two backends itself, like p2/p3-verify do)
# ============================================================================
param(
    [string]$MysqlPassword = '521131',
    [int]$WaitSeconds = 70
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$portal = 'http://localhost:8090'
$admin = 'http://localhost:8081'
$mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$java = 'D:\jdks\jdk-17.0.12\bin\java.exe'
$outDir = Join-Path $root 'tmp\p1-timeout'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$reqFile = Join-Path $outDir '_request.json'
$script:results = New-Object System.Collections.Generic.List[object]
. (Join-Path $PSScriptRoot 'e2e-lib.ps1')

$CAT = 'Timeout Job Cat'
$ITEM = 'Timeout Job Item'
$SPEC = 'TIMEOUT-A'
$BUYER = 'timeouttest1'
$BUYER_PWD = 'timeouttest123456'
$STOCK = 10

$adminLog = Join-Path $root 'logs\admin.log'
$portalLog = Join-Path $root 'logs\portal.log'

# --------------------------------------------------------------------------- helpers
function Sql([string]$q) { return (Invoke-Mysql "USE mall; $q").Trim() }

function Purge {
    Sql ("DELETE FROM t_order_item WHERE product_name = '$ITEM'; " +
        "DELETE o FROM t_order o WHERE o.user_id IN (SELECT id FROM t_user WHERE username = '$BUYER'); " +
        "DELETE FROM t_cart WHERE user_id IN (SELECT id FROM t_user WHERE username = '$BUYER'); " +
        "DELETE FROM t_address WHERE user_id IN (SELECT id FROM t_user WHERE username = '$BUYER'); " +
        "DELETE FROM t_user WHERE username = '$BUYER'; " +
        "DELETE s FROM t_sku s JOIN t_product p ON s.product_id = p.id WHERE p.name = '$ITEM'; " +
        "DELETE FROM t_product WHERE name = '$ITEM'; " +
        "DELETE FROM t_category WHERE name = '$CAT';") | Out-Null
}

# Sum of every "[timeout-job] closed N" already in a log -> used as a baseline so we
# only count the runs triggered by this script.
function ClosedCount([string]$logPath) {
    if (-not (Test-Path $logPath)) { return 0 }
    $text = Read-Text $logPath
    $sum = 0
    foreach ($m in [regex]::Matches($text, '\[timeout-job\] closed (\d+)')) { $sum += [int]$m.Groups[1].Value }
    return $sum
}

function SkuState([long]$productId) {
    return (Sql "SELECT CONCAT(stock,',',IFNULL(locked_stock,0)) FROM t_sku WHERE product_id = $productId LIMIT 1;")
}

function Wait-OrderStatus([long]$orderId, [int]$status, [int]$sec) {
    $deadline = (Get-Date).AddSeconds($sec)
    while ((Get-Date) -lt $deadline) {
        if ((Sql "SELECT status FROM t_order WHERE id = $orderId;") -eq [string]$status) { return $true }
        Start-Sleep -Milliseconds 1000
    }
    return $false
}

# --------------------------------------------------------------------------- setup
# 先把两个应用都起起来：setup 阶段要用 admin 接口建测试商品
Write-Host '== setup: start both apps first =='
Stop-MallApps | Out-Null
if (-not (Start-MallApps 'setup')) {
    Check 'setup: both apps started' $false 'see logs\admin.log / logs\portal.log'
    $fail = Save-Summary 'Timeout job verification (single-point + exactly-once)'
    exit $fail
}
Check 'setup: both apps started' $true 'logs\admin.log + logs\portal.log'

Purge
Write-Host '   leftovers purged'

$adm = Http-Json -Method POST -Url "$admin/api/admin/auth/login" -Body @{ username = 'malladmin'; password = 'admin123456' } -Name '01-admin-login'
$adminToken = (Payload $adm).token
$cat = Http-Json -Method POST -Url "$admin/api/admin/category" -Token $adminToken -Body @{ parentId = 0; name = $CAT; sort = 91 } -Name '02-cat'
$prod = Http-Json -Method POST -Url "$admin/api/admin/product" -Token $adminToken -Body @{
    categoryId = (Payload $cat); name = $ITEM; subTitle = 'timeout job'; price = 10
    skus = @( @{ skuCode = $SPEC; spec = $SPEC; price = 10; stock = $STOCK } )
} -Name '03-product'
$prodId = [long](Payload $prod)
$skuId = [long](Sql "SELECT id FROM t_sku WHERE product_id = $prodId LIMIT 1;")
Check 'setup: test product ready' ($prodId -gt 0 -and $skuId -gt 0) "productId=$prodId skuId=$skuId stock=$STOCK"

Http-Json -Method POST -Url "$portal/api/portal/auth/register" -Body @{ username = $BUYER; password = $BUYER_PWD; nickname = 'Timeout Buyer' } -Name '04-register' | Out-Null
$login = Http-Json -Method POST -Url "$portal/api/portal/auth/login" -Body @{ username = $BUYER; password = $BUYER_PWD } -Name '05-login'
$token = (Payload $login).token
$addr = Http-Json -Method POST -Url "$portal/api/portal/address" -Token $token -Body @{
    receiver = 'Timeout Buyer'; phone = '13500000000'; province = 'GD'; city = 'SZ'; district = 'NS'
    detail = 'Timeout Road 1'; isDefault = 1
} -Name '06-address'
$addrId = [long](Payload $addr)
Check 'setup: buyer + address ready' ($null -ne $token -and $addrId -gt 0) "addressId=$addrId"

# 下单接口在 portal 上，所以「建单」和「让它过期」分成两步：
# C 阶段需要「先在两个应用都活着时建单，再停掉 portal，然后才让它过期」，这样才能证明是 admin 关的。
function New-Order([string]$tag) {
    Http-Json -Method POST -Url "$portal/api/portal/cart" -Token $token -Body @{ skuId = $skuId; quantity = 2 } -Name "$tag-cart" | Out-Null
    $order = Http-Json -Method POST -Url "$portal/api/portal/order" -Token $token -Body @{ addressId = $addrId; requestId = $tag } -Name "$tag-order"
    return [long](Payload $order).orderId
}

function Expire-Now([long]$orderId) {
    Sql "UPDATE t_order SET expire_time = DATE_SUB(NOW(), INTERVAL 1 MINUTE) WHERE id = $orderId;" | Out-Null
}

# ============================================================ A) portal alone
Write-Host ''
Write-Host '== A) only mall-portal running (admin stopped) =='
Stop-MallApps | Out-Null
Start-MallApp 'portal' (Join-Path $root 'backend\mall-portal\target\mall-portal-1.0.0.jar') $portalLog | Out-Null
$portalUp = Wait-Log $portalLog 'Started MallPortalApplication' 120
Check 'A0 mall-portal started (admin intentionally NOT running)' $portalUp 'logs\portal.log'

if ($portalUp) {
    $adminBase = ClosedCount $adminLog
    $portalBase = ClosedCount $portalLog

    $orderA = New-Order 'race-timeout-A'
    Expire-Now $orderA
    Check 'A1 expired order created while admin is down' ($orderA -gt 0) "orderId=$orderA sku=$(SkuState $prodId) (expect stock,2)"
    Check 'A2 order was only locking stock, not deducting it' ((SkuState $prodId) -eq "$STOCK,2") "sku=$(SkuState $prodId)"

    $closedA = Wait-OrderStatus $orderA 4 $WaitSeconds
    Check 'A3 mall-portal ALONE closed the expired order' $closedA "status=$(Sql "SELECT status FROM t_order WHERE id = $orderA;") (expect 4)"
    Check 'A4 locked stock released back to 0' ((SkuState $prodId) -eq "$STOCK,0") "sku=$(SkuState $prodId)"
    Check 'A5 exactly one close was logged, by portal' (
        ((ClosedCount $portalLog) - $portalBase) -eq 1 -and ((ClosedCount $adminLog) - $adminBase) -eq 0
    ) "portalDelta=$((ClosedCount $portalLog) - $portalBase) adminDelta=$((ClosedCount $adminLog) - $adminBase)"
}

# ============================================================ B) both apps, exactly once
Write-Host ''
Write-Host '== B) both apps scanning the same order =='
Start-MallApp 'admin' (Join-Path $root 'backend\mall-admin\target\mall-admin-1.0.0.jar') $adminLog | Out-Null
$adminUp = Wait-Log $adminLog 'Started MallAdminApplication' 120
Check 'B0 mall-admin started too' $adminUp 'logs\admin.log'

if ($adminUp) {
    $adminBase = ClosedCount $adminLog
    $portalBase = ClosedCount $portalLog

    $orderB = New-Order 'race-timeout-B'
    Expire-Now $orderB
    Check 'B1 expired order created' ($orderB -gt 0) "orderId=$orderB"

    $closedB = Wait-OrderStatus $orderB 4 $WaitSeconds
    Check 'B2 order closed' $closedB "status=$(Sql "SELECT status FROM t_order WHERE id = $orderB;") (expect 4)"

    # 等一个完整的扫描周期，让两个应用都有机会重复处理同一单
    Start-Sleep -Seconds 15
    $portalDelta = (ClosedCount $portalLog) - $portalBase
    $adminDelta = (ClosedCount $adminLog) - $adminBase
    Check 'B3 closed exactly once across BOTH apps (no double release)' ($portalDelta + $adminDelta) -eq 1 "portalDelta=$portalDelta adminDelta=$adminDelta"
    Check 'B4 locked stock released back to 0' ((SkuState $prodId) -eq "$STOCK,0") "sku=$(SkuState $prodId)"
    Check 'B5 no paid order was touched (only unpaid ones are closed)' (
        (Sql "SELECT COUNT(*) FROM t_order WHERE user_id = (SELECT id FROM t_user WHERE username = '$BUYER') AND status = 0;") -eq '0'
    ) "pending orders left = $(Sql "SELECT COUNT(*) FROM t_order WHERE user_id = (SELECT id FROM t_user WHERE username = '$BUYER') AND status = 0;")"
}

# ============================================================ C) admin alone
Write-Host ''
Write-Host '== C) only mall-admin running (portal stopped) =='
# 下单接口在 portal 上，所以先在两个应用都活着时把订单建出来，再停掉 portal，最后才让它过期
$orderC = New-Order 'race-timeout-C'
Check 'C0 order created while both apps were up' ($orderC -gt 0) "orderId=$orderC"

Stop-MallApps | Out-Null
Start-MallApp 'admin' (Join-Path $root 'backend\mall-admin\target\mall-admin-1.0.0.jar') $adminLog | Out-Null
$adminAlone = Wait-Log $adminLog 'Started MallAdminApplication' 120
Check 'C1 mall-admin started (portal intentionally NOT running)' $adminAlone 'logs\admin.log'

if ($adminAlone) {
    $adminBase = ClosedCount $adminLog
    Expire-Now $orderC
    $closedC = Wait-OrderStatus $orderC 4 $WaitSeconds
    Check 'C2 mall-admin ALONE closed the expired order' $closedC "status=$(Sql "SELECT status FROM t_order WHERE id = $orderC;") (expect 4)"
    Check 'C3 exactly one close logged by admin' (((ClosedCount $adminLog) - $adminBase) -eq 1) "adminDelta=$((ClosedCount $adminLog) - $adminBase)"
    Check 'C4 locked stock released back to 0' ((SkuState $prodId) -eq "$STOCK,0") "sku=$(SkuState $prodId)"
}

# ============================================================ restore + cleanup
Write-Host ''
Write-Host '== restore both apps, then cleanup =='
Stop-MallApps | Out-Null
Start-MallApps 'restore' | Out-Null
Purge
Check 'cleanup: test data removed' ([int](Sql "SELECT COUNT(*) FROM t_user WHERE username = '$BUYER';") -eq 0) 'leftover users = 0'

$fail = Save-Summary 'Timeout job verification (single-point + exactly-once)'
exit $fail
