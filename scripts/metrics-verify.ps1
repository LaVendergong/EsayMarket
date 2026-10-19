# ============================================================================
# Metrics / observability verification.
#
# Why: /actuator/health only tells you the process is alive. This script proves
# the Prometheus endpoint is really exposed and that the business counters we
# instrumented actually move when the business happens:
#
#   A) /actuator/prometheus is scrapeable on BOTH apps (plus generic JVM/HTTP)
#   B) creating an order increments mall_orders_created_total by exactly 1
#   C) an idempotent replay (same requestId) does NOT increment it again
#   D) a stock shortage increments mall_orders_stock_shortage_total
#
# ASCII-only on purpose (see docs/AI-HANDOFF.md section 8, item 28).
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File D:\projects\mall\scripts\metrics-verify.ps1
# ============================================================================
param(
    [string]$MysqlPassword = '521131'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$portal = 'http://localhost:8090'
$admin = 'http://localhost:8081'
$mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$java = 'D:\jdks\jdk-17.0.12\bin\java.exe'
$outDir = Join-Path $root 'tmp\metrics'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$reqFile = Join-Path $outDir '_request.json'
$script:results = New-Object System.Collections.Generic.List[object]
. (Join-Path $PSScriptRoot 'e2e-lib.ps1')

$CAT = 'Metrics Cat'
$ITEM = 'Metrics Item'
$SPEC = 'METRICS-A'
$BUYER = 'metricsuser1'
$BUYER_PWD = 'metrics123456'
$STOCK = 5

function Sql([string]$q) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $mysql -uroot "-p$MysqlPassword" -N --default-character-set=utf8mb4 -e "USE mall; $q" 2>$null
        return ($out | Out-String).Trim()
    } finally { $ErrorActionPreference = $prev }
}

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

# read a counter value out of the Prometheus text exposition; -1 when the metric is absent
function Metric([string]$url, [string]$name) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $body = (curl.exe -s -m 10 $url 2>$null | Out-String)
    } finally { $ErrorActionPreference = $prev }
    if (-not $body) { return -1 }
    # 不要用 $ 做行尾锚点：curl 输出经 PowerShell 之后行尾是 \r\n，$ 前会多一个 \r 匹配不上
    $m = [regex]::Match($body, "(?m)^$([regex]::Escape($name))(?:\{[^}]*\})?\s+([0-9.eE+-]+)")
    if ($m.Success) { return [double]$m.Groups[1].Value }
    return -1
}

# --------------------------------------------------------------------------- setup
Write-Host '== setup =='
Stop-MallApps | Out-Null
if (-not (Start-MallApps 'metrics')) {
    Check 'setup: both apps started' $false 'see logs'
    $fail = Save-Summary 'Metrics verification'
    exit $fail
}
Check 'setup: both apps started' $true 'logs'

Purge
$adm = Http-Json -Method POST -Url "$admin/api/v1/admin/auth/login" -Body @{ username = 'malladmin'; password = 'admin123456' } -Name '01-admin'
$adminToken = (Payload $adm).token
$cat = Http-Json -Method POST -Url "$admin/api/v1/admin/category" -Token $adminToken -Body @{ parentId = 0; name = $CAT; sort = 93 } -Name '02-cat'
$prod = Http-Json -Method POST -Url "$admin/api/v1/admin/product" -Token $adminToken -Body @{
    categoryId = (Payload $cat); name = $ITEM; subTitle = 'metrics'; price = 10
    skus = @( @{ skuCode = $SPEC; spec = $SPEC; price = 10; stock = $STOCK } )
} -Name '03-product'
$prodId = [long](Payload $prod)
$skuId = [long](Sql "SELECT id FROM t_sku WHERE product_id = $prodId LIMIT 1;")
Check 'setup: test product ready' ($prodId -gt 0 -and $skuId -gt 0) "productId=$prodId skuId=$skuId stock=$STOCK"

Http-Json -Method POST -Url "$portal/api/v1/portal/auth/register" -Body @{ username = $BUYER; password = $BUYER_PWD; nickname = 'M' } -Name '04-reg' | Out-Null
$login = Http-Json -Method POST -Url "$portal/api/v1/portal/auth/login" -Body @{ username = $BUYER; password = $BUYER_PWD } -Name '05-login'
$token = (Payload $login).token
$addr = Http-Json -Method POST -Url "$portal/api/v1/portal/address" -Token $token -Body @{
    receiver = 'Metrics'; phone = '13600000000'; province = 'GD'; city = 'SZ'; district = 'NS'
    detail = 'Metrics Road 1'; isDefault = 1
} -Name '06-addr'
$addrId = [long](Payload $addr)
Check 'setup: buyer + address ready' ($null -ne $token -and $addrId -gt 0) "addressId=$addrId"

# ============================================================ A) endpoint exposed
Write-Host ''
Write-Host '== A) prometheus endpoint =='
$portalUrl = "$portal/actuator/prometheus"
$adminUrl = "$admin/actuator/prometheus"
$portalBody = ''
$prevEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try { $portalBody = (curl.exe -s -m 10 $portalUrl 2>$null | Out-String) } finally { $ErrorActionPreference = $prevEap }

Check 'A1 portal /actuator/prometheus returns metrics text' ($portalBody.Length -gt 500 -and $portalBody -match 'jvm_memory_used_bytes') "bytes=$($portalBody.Length)"
Check 'A2 http server metrics are exposed' ($portalBody -match 'http_server_requests_seconds_count') 'http_server_requests_seconds_count'
Check 'A3 business counters are registered' ($portalBody -match 'mall_orders_created_total' -and $portalBody -match 'mall_orders_stock_shortage_total') 'mall_orders_*'
Check 'A4 admin app exposes prometheus too' ((Metric $adminUrl 'jvm_classes_loaded_classes') -ge 0) 'jvm_classes_loaded_classes'

# ============================================================ B) created +1
Write-Host ''
Write-Host '== B) creating an order moves the counter by exactly 1 =='
$before = Metric $portalUrl 'mall_orders_created_total'
Http-Json -Method POST -Url "$portal/api/v1/portal/cart" -Token $token -Body @{ skuId = $skuId; quantity = 1 } -Name '10-cart' | Out-Null
$order1 = Http-Json -Method POST -Url "$portal/api/v1/portal/order" -Token $token -Body @{ addressId = $addrId; requestId = 'metrics-1' } -Name '11-order'
$orderId1 = [long](Payload $order1).orderId
Check 'B1 order created' ($orderId1 -gt 0) "orderId=$orderId1 code=$(Code $order1)"
$after = Metric $portalUrl 'mall_orders_created_total'
Check 'B2 mall_orders_created_total increased by exactly 1' ($before -ge 0 -and ($after - $before) -eq 1) "$before -> $after"

# ============================================================ C) replay must not count
Write-Host ''
Write-Host '== C) idempotent replay does not inflate the counter =='
$mid = Metric $portalUrl 'mall_orders_created_total'
$replay = Http-Json -Method POST -Url "$portal/api/v1/portal/order" -Token $token -Body @{ addressId = $addrId; requestId = 'metrics-1' } -Name '20-replay'
$replayId = [long](Payload $replay).orderId
$afterReplay = Metric $portalUrl 'mall_orders_created_total'
Check 'C1 replay returns the same order' ($replayId -eq $orderId1) "replay=$replayId original=$orderId1"
Check 'C2 counter unchanged after replay' ($afterReplay -eq $mid) "$mid -> $afterReplay"

# ============================================================ D) stock shortage
Write-Host ''
Write-Host '== D) stock shortage moves its own counter =='
# 注意：不能靠「加购一个超大数量」来制造库存不足 —— 加购阶段自己就会校验可售库存并返回 2003，
# 订单根本到不了锁库存那一步（第一版就是这么写的，结果断言失败）。
# 正确做法：正常加购，然后把 SKU 的总库存扣到 0，让下单时的条件 UPDATE 失败。
$shortBefore = Metric $portalUrl 'mall_orders_stock_shortage_total'
Http-Json -Method POST -Url "$portal/api/v1/portal/cart" -Token $token -Body @{ skuId = $skuId; quantity = 1 } -Name '30-cart' | Out-Null
Sql "UPDATE t_sku SET stock = 0 WHERE id = $skuId;" | Out-Null
$tooMany = Http-Json -Method POST -Url "$portal/api/v1/portal/order" -Token $token -Body @{ addressId = $addrId; requestId = 'metrics-2' } -Name '31-order-no-stock'
$shortAfter = Metric $portalUrl 'mall_orders_stock_shortage_total'
Check 'D1 order rejected with 2003 when stock ran out' ((Code $tooMany) -eq 2003) "code=$(Code $tooMany)"
Check 'D2 mall_orders_stock_shortage_total increased' ($shortBefore -ge 0 -and ($shortAfter - $shortBefore) -ge 1) "$shortBefore -> $shortAfter"

# ============================================================ cleanup
Write-Host ''
Write-Host '== cleanup =='
Purge
Check 'cleanup: test data removed' ([int](Sql "SELECT COUNT(*) FROM t_user WHERE username = '$BUYER';") -eq 0) 'leftover users = 0'

$fail = Save-Summary 'Metrics verification (prometheus + business counters)'
exit $fail
