# ============================================================================
# Order-idempotency verification.
#
# Why this exists: create() used to be "read checked cart rows -> lock stock ->
# insert order -> delete checked rows". Two concurrent submits both read the same
# rows, so both created an order and both locked stock again (no oversell, but
# duplicate orders and double-locked stock).
#
# What it proves:
#   A) same requestId, concurrent   -> both return the SAME order, only one row, stock locked once
#   B) different requestIds, concurrent -> exactly one wins, the other gets 2004 (cart lock serialises)
#   C) same requestId, sequential retry -> returns the original order, nothing changes
#   D) no requestId, concurrent     -> still only one order (cart row lock is the fallback)
#   E) over-long requestId          -> 400, rejected before any write
#
# ASCII-only on purpose: write_to_file emits no BOM and Windows PowerShell 5.1 would
# parse a BOM-less .ps1 as GBK (see docs/AI-HANDOFF.md section 8, items 11 and 28).
#
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File D:\projects\mall\scripts\idempotency-verify.ps1
#        (requires both backends already running)
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
$outDir = Join-Path $root 'tmp\p1-idem'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$reqFile = Join-Path $outDir '_request.json'
$script:results = New-Object System.Collections.Generic.List[object]
. (Join-Path $PSScriptRoot 'e2e-lib.ps1')
# HttpClient is needed to fire two truly overlapping POSTs from one PowerShell process
try { Add-Type -AssemblyName System.Net.Http -ErrorAction SilentlyContinue } catch { }

$CAT = 'Idem Verify Cat'
$ITEM = 'Idem Verify Item'
$SPEC = 'IDEM-A'
$BUYER = 'idemtest1'
$BUYER_PWD = 'idemtest123456'
$STOCK = 100
$PRICE = 10

# --------------------------------------------------------------------------- helpers
function Sql([string]$q) { return (Invoke-Mysql "USE mall; $q").Trim() }

function SkuState([long]$productId) {
    return (Sql "SELECT CONCAT(stock,',',IFNULL(locked_stock,0)) FROM t_sku WHERE product_id = $productId LIMIT 1;")
}
function LockedOf([long]$productId) {
    return [int](Sql "SELECT IFNULL(locked_stock,0) FROM t_sku WHERE product_id = $productId LIMIT 1;")
}
function OrderCountByRequest([long]$userId, [string]$requestId) {
    return [int](Sql "SELECT COUNT(*) FROM t_order WHERE user_id = $userId AND request_id = '$requestId';")
}
function OrderCountAll([long]$userId) {
    return [int](Sql "SELECT COUNT(*) FROM t_order WHERE user_id = $userId;")
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

# Fire a POST without awaiting it, so a second one can genuinely overlap
function Start-RaceOrder([string]$token, [string]$bodyJson) {
    $client = New-Object System.Net.Http.HttpClient
    $client.DefaultRequestHeaders.Add('Authorization', "Bearer $token")
    $content = New-Object System.Net.Http.StringContent($bodyJson, [System.Text.Encoding]::UTF8, 'application/json')
    return [pscustomobject]@{ client = $client; task = $client.PostAsync("$portal/api/portal/order", $content) }
}
function Complete-RaceOrder($race, [string]$tag) {
    $resp = $race.task.GetAwaiter().GetResult()
    $body = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    Write-Text (Join-Path $outDir ("$tag.json")) $body
    $parsed = $null
    if ($body) { try { $parsed = $body | ConvertFrom-Json } catch { } }
    $race.client.Dispose()
    return [pscustomobject]@{ http = [int]$resp.StatusCode; body = $parsed }
}
function RaceCode($resp) { if ($resp.body) { return $resp.body.code } else { return $null } }
function RaceOrderId($resp) { if ($resp.body -and $resp.body.data) { return $resp.body.data.orderId } else { return $null } }

# --------------------------------------------------------------------------- setup
Write-Host '== setup =='
Purge
Write-Host '   leftovers purged'

$adm = Http-Json -Method POST -Url "$admin/api/admin/auth/login" -Body @{ username = 'malladmin'; password = 'admin123456' } -Name '01-admin-login'
# 注意：Payload 返回的是 body.data（整个 LoginVO 对象），这里要取 .token，别把对象当 token 用
$adminToken = (Payload $adm).token
Check 'setup: admin login' ((Code $adm) -eq 200 -and $null -ne $adminToken) "code=$(Code $adm)"

$cat = Http-Json -Method POST -Url "$admin/api/admin/category" -Token $adminToken -Body @{ parentId = 0; name = $CAT; sort = 92 } -Name '02-cat'
$catId = Payload $cat
Check 'setup: category created' ($null -ne $catId -and [long]$catId -gt 0) "code=$(Code $cat) categoryId=$catId"
$prod = Http-Json -Method POST -Url "$admin/api/admin/product" -Token $adminToken -Body @{
    categoryId = $catId; name = $ITEM; subTitle = 'idempotency'; price = $PRICE
    skus = @( @{ skuCode = $SPEC; spec = $SPEC; price = $PRICE; stock = $STOCK } )
} -Name '03-product'
$prodId = [long](Payload $prod)
$skuId = [long](Sql "SELECT id FROM t_sku WHERE product_id = $prodId LIMIT 1;")
Check 'setup: test product ready' ($prodId -gt 0 -and $skuId -gt 0) "productId=$prodId skuId=$skuId stock=$STOCK"

Http-Json -Method POST -Url "$portal/api/portal/auth/register" -Body @{ username = $BUYER; password = $BUYER_PWD; nickname = 'Idem Buyer' } -Name '04-register' | Out-Null
$login = Http-Json -Method POST -Url "$portal/api/portal/auth/login" -Body @{ username = $BUYER; password = $BUYER_PWD } -Name '05-login'
$token = (Payload $login).token
$me = Http-Json -Method GET -Url "$portal/api/portal/user/me" -Token $token -Name '06-me'
$userId = [long](Payload $me).id
Check 'setup: buyer registered and logged in' ($null -ne $token -and $userId -gt 0) "userId=$userId"

$addr = Http-Json -Method POST -Url "$portal/api/portal/address" -Token $token -Body @{
    receiver = 'Idem Buyer'; phone = '13600000000'; province = 'GD'; city = 'SZ'; district = 'NS'
    detail = 'Idem Road 1'; isDefault = 1
} -Name '07-address'
$addrId = [long](Payload $addr)
Check 'setup: address created' ($addrId -gt 0) "addressId=$addrId"

# --------------------------------------------------------------------------- A) same requestId, concurrent
Write-Host ''
Write-Host '== A) same requestId fired twice concurrently =='
Http-Json -Method POST -Url "$portal/api/portal/cart" -Token $token -Body @{ skuId = $skuId; quantity = 2 } -Name '10-cart-A' | Out-Null
$lockedBefore = LockedOf $prodId
$bodyA = @{ addressId = $addrId; requestId = 'race-A' } | ConvertTo-Json -Compress
# 先把两个请求都发出去（都不 await），再一起收结果 —— 否则就是串行，测不出并发问题
$raceA1 = Start-RaceOrder $token $bodyA
$raceA2 = Start-RaceOrder $token $bodyA
$r1 = Complete-RaceOrder $raceA1 '11-race-A-1'
$r2 = Complete-RaceOrder $raceA2 '11-race-A-2'
$codesA = @((RaceCode $r1), (RaceCode $r2))
$orderA1 = RaceOrderId $r1
$orderA2 = RaceOrderId $r2
Check 'A1 both concurrent submits succeed' (@($codesA | Where-Object { $_ -eq 200 }).Count -eq 2) "codes=$($codesA -join ',')"
Check 'A2 both return the SAME orderId (idempotent)' ($null -ne $orderA1 -and $orderA1 -eq $orderA2) "orderIds=$orderA1,$orderA2"
Check 'A3 only ONE order row for that requestId' ((OrderCountByRequest $userId 'race-A') -eq 1) "rows=$(OrderCountByRequest $userId 'race-A')"
Check 'A4 stock locked exactly once (delta=2)' ((LockedOf $prodId) -eq ($lockedBefore + 2)) "locked $lockedBefore -> $(LockedOf $prodId) (expect +2)"
Check 'A5 cart checked rows were consumed' ([int](Sql "SELECT COUNT(*) FROM t_cart WHERE user_id = $userId AND checked = 1;") -eq 0) 'checked rows = 0'

# --------------------------------------------------------------------------- B) different requestIds, concurrent
Write-Host ''
Write-Host '== B) different requestIds fired concurrently =='
Http-Json -Method POST -Url "$portal/api/portal/cart" -Token $token -Body @{ skuId = $skuId; quantity = 3 } -Name '20-cart-B' | Out-Null
$lockedBefore = LockedOf $prodId
$ordersBefore = OrderCountAll $userId
$raceB1 = Start-RaceOrder $token (@{ addressId = $addrId; requestId = 'race-B1' } | ConvertTo-Json -Compress)
$raceB2 = Start-RaceOrder $token (@{ addressId = $addrId; requestId = 'race-B2' } | ConvertTo-Json -Compress)
$r3 = Complete-RaceOrder $raceB1 '21-race-B-1'
$r4 = Complete-RaceOrder $raceB2 '21-race-B-2'
$codesB = @((RaceCode $r3), (RaceCode $r4))
Check 'B1 exactly one wins, the other gets 2004 (cart empty)' (
    @($codesB | Where-Object { $_ -eq 200 }).Count -eq 1 -and @($codesB | Where-Object { $_ -eq 2004 }).Count -eq 1
) "codes=$($codesB -join ',')"
Check 'B2 only ONE extra order row' ((OrderCountAll $userId) -eq ($ordersBefore + 1)) "orders $ordersBefore -> $(OrderCountAll $userId)"
Check 'B3 stock locked exactly once (delta=3)' ((LockedOf $prodId) -eq ($lockedBefore + 3)) "locked $lockedBefore -> $(LockedOf $prodId) (expect +3)"

# --------------------------------------------------------------------------- C) sequential retry, same requestId
Write-Host ''
Write-Host '== C) sequential retry with the same requestId =='
$lockedBefore = LockedOf $prodId
$ordersBefore = OrderCountAll $userId
# 注意：Http-Json 内部会 ConvertTo-Json，这里必须传 hashtable，传已序列化的字符串会被二次编码
$retry = Http-Json -Method POST -Url "$portal/api/portal/order" -Token $token -Body @{ addressId = $addrId; requestId = 'race-A' } -Name '30-retry-A'
Check 'C1 retry returns the original order' ((Code $retry) -eq 200 -and (Payload $retry).orderId -eq $orderA1) "code=$(Code $retry) orderId=$((Payload $retry).orderId) expect=$orderA1"
Check 'C2 retry created no new order' ((OrderCountAll $userId) -eq $ordersBefore) "orders=$ordersBefore -> $(OrderCountAll $userId)"
Check 'C3 retry did not lock stock again' ((LockedOf $prodId) -eq $lockedBefore) "locked $lockedBefore -> $(LockedOf $prodId)"

# --------------------------------------------------------------------------- D) no requestId, concurrent
Write-Host ''
Write-Host '== D) no requestId at all, concurrent (old clients) =='
Http-Json -Method POST -Url "$portal/api/portal/cart" -Token $token -Body @{ skuId = $skuId; quantity = 1 } -Name '40-cart-D' | Out-Null
$lockedBefore = LockedOf $prodId
$ordersBefore = OrderCountAll $userId
$noId = @{ addressId = $addrId } | ConvertTo-Json -Compress
$raceD1 = Start-RaceOrder $token $noId
$raceD2 = Start-RaceOrder $token $noId
$r5 = Complete-RaceOrder $raceD1 '41-race-D-1'
$r6 = Complete-RaceOrder $raceD2 '41-race-D-2'
$codesD = @((RaceCode $r5), (RaceCode $r6))
Check 'D1 exactly one order created even without a requestId' (
    @($codesD | Where-Object { $_ -eq 200 }).Count -eq 1 -and @($codesD | Where-Object { $_ -eq 2004 }).Count -eq 1
) "codes=$($codesD -join ',')"
Check 'D2 only ONE extra order row' ((OrderCountAll $userId) -eq ($ordersBefore + 1)) "orders $ordersBefore -> $(OrderCountAll $userId)"
Check 'D3 stock locked exactly once (delta=1)' ((LockedOf $prodId) -eq ($lockedBefore + 1)) "locked $lockedBefore -> $(LockedOf $prodId)"

# --------------------------------------------------------------------------- E) validation
Write-Host ''
Write-Host '== E) over-long requestId =='
$longId = 'x' * 65
$tooLong = Http-Json -Method POST -Url "$portal/api/portal/order" -Token $token -Body @{ addressId = $addrId; requestId = $longId } -Name '50-too-long'
Check 'E1 over-long requestId rejected with 400' ((Code $tooLong) -eq 400) "code=$(Code $tooLong) http=$($tooLong.http)"

# --------------------------------------------------------------------------- F) malformed body
Write-Host ''
Write-Host '== F) malformed request body =='
# 传字符串而不是对象 -> Http-Json 会再序列化一次，服务端收到「字符串形式的 JSON」
# 以前会落到 Exception 兜底变成 code 500，现已按 400 处理（客户端的错不该算服务端错误）
$bad = Http-Json -Method POST -Url "$portal/api/portal/order" -Token $token -Body '{"addressId":1}' -Name '60-bad-body'
Check 'F1 malformed body -> 400, not 500' ((Code $bad) -eq 400) "code=$(Code $bad) http=$($bad.http)"

# --------------------------------------------------------------------------- cleanup
Write-Host ''
Write-Host '== cleanup =='
Purge
Check 'cleanup: test data removed' ([int](Sql "SELECT COUNT(*) FROM t_user WHERE username = '$BUYER';") -eq 0) 'leftover users = 0'

$fail = Save-Summary 'Order idempotency verification'
exit $fail
