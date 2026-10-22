# ============================================================================
# 支付链路验证（支付单 / 渠道 / 幂等 / 回调 / 越权）
#
# 之前「支付」就是一个接口把订单改成已支付：没有支付单、没有渠道、没有回调、没有对账依据。
# 现在有了支付单和三个渠道（MOCK / 支付宝 / 微信），这个脚本守住几条底线：
#   1) 只有待支付订单能创建支付单；金额取订单实付
#   2) 同一订单重复创建支付单时，旧的待支付单要被关掉（否则两笔都能付成功）
#   3) 支付成功只扣一次库存、只加一次销量（重复调用/重复回调都幂等）
#   4) 回调必须验签；伪造的回调不能改任何状态
#   5) 支付单只能本人看
#
# ASCII-only on purpose (see docs/AI-HANDOFF.md section 8, item 28).
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File D:\projects\mall\scripts\payment-verify.ps1
# ============================================================================
param(
    [string]$MysqlPassword = '521131'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
# e2e-lib 依赖调用方作用域里的这些变量
$portal = 'http://localhost:8090'
$admin = 'http://localhost:8081'
$mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$java = 'D:\jdks\jdk-17.0.12\bin\java.exe'
$outDir = Join-Path $root 'tmp\payment'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$reqFile = Join-Path $outDir '_request.json'
$script:results = New-Object System.Collections.Generic.List[object]
. (Join-Path $PSScriptRoot 'e2e-lib.ps1')

$STAMP = (Get-Date -Format 'HHmmss')
$BUYER = "pay$STAMP"
$OTHER = "payother$STAMP"
$PWD_OK = 'pay123456'
$CAT = "Pay Cat $STAMP"
$ITEM = "Pay Item $STAMP"
$SPEC = "PAY-SPEC-$STAMP"
$PRICE = 50

function Sql([string]$q) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $mysql -uroot "-p$MysqlPassword" -N --default-character-set=utf8mb4 -e "USE mall; $q" 2>$null
        return ($out | Out-String).Trim()
    } finally { $ErrorActionPreference = $prev }
}

function Purge {
    Sql ("DELETE FROM t_payment WHERE user_id IN (SELECT id FROM t_user WHERE username IN ('$BUYER','$OTHER')); " +
        "DELETE FROM t_after_sale WHERE user_id IN (SELECT id FROM t_user WHERE username IN ('$BUYER','$OTHER')); " +
        "DELETE FROM t_order_item WHERE order_id IN (SELECT id FROM t_order WHERE user_id IN (SELECT id FROM t_user WHERE username IN ('$BUYER','$OTHER'))); " +
        "DELETE FROM t_order WHERE user_id IN (SELECT id FROM t_user WHERE username IN ('$BUYER','$OTHER')); " +
        "DELETE FROM t_cart WHERE user_id IN (SELECT id FROM t_user WHERE username IN ('$BUYER','$OTHER')); " +
        "DELETE FROM t_address WHERE user_id IN (SELECT id FROM t_user WHERE username IN ('$BUYER','$OTHER')); " +
        "DELETE FROM t_user WHERE username IN ('$BUYER','$OTHER'); " +
        "DELETE s FROM t_sku s JOIN t_product p ON s.product_id = p.id WHERE p.name = '$ITEM'; " +
        "DELETE FROM t_product WHERE name = '$ITEM'; " +
        "DELETE FROM t_category WHERE name = '$CAT';") | Out-Null
}

# --------------------------------------------------------------------------- setup
Write-Host '== setup =='
Stop-MallApps | Out-Null
if (-not (Start-MallApps 'payment')) {
    Check 'setup: both apps started' $false 'see logs'
    $fail = Save-Summary 'Payment flow verification'
    exit $fail
}
Check 'setup: both apps started' $true 'logs'

Purge
$adm = Http-Json -Method POST -Url "$admin/api/v1/admin/auth/login" -Body @{ username = 'malladmin'; password = 'admin123456' } -Name '01-admin'
$adminToken = (Payload $adm).token
$cat = Http-Json -Method POST -Url "$admin/api/v1/admin/category" -Token $adminToken -Body @{ parentId = 0; name = $CAT; sort = 98 } -Name '02-cat'
$prod = Http-Json -Method POST -Url "$admin/api/v1/admin/product" -Token $adminToken -Body @{
    categoryId = (Payload $cat); name = $ITEM; subTitle = 'payment'; price = $PRICE
    skus = @( @{ skuCode = $SPEC; spec = $SPEC; price = $PRICE; stock = 100 } )
} -Name '03-product'
$prodId = [long](Payload $prod)
$skuId = [long](Sql "SELECT id FROM t_sku WHERE product_id = $prodId LIMIT 1;")
Check 'setup: product/sku ready' ($skuId -gt 0) "skuId=$skuId"

Http-Json -Method POST -Url "$portal/api/v1/portal/auth/register" -Body @{ username = $BUYER; password = $PWD_OK; nickname = 'P' } -Name '04-reg' | Out-Null
Http-Json -Method POST -Url "$portal/api/v1/portal/auth/register" -Body @{ username = $OTHER; password = $PWD_OK; nickname = 'O' } -Name '05-reg2' | Out-Null
$login = Http-Json -Method POST -Url "$portal/api/v1/portal/auth/login" -Body @{ username = $BUYER; password = $PWD_OK } -Name '06-login'
$token = (Payload $login).token
$loginOther = Http-Json -Method POST -Url "$portal/api/v1/portal/auth/login" -Body @{ username = $OTHER; password = $PWD_OK } -Name '07-login2'
$otherToken = (Payload $loginOther).token
$addr = Http-Json -Method POST -Url "$portal/api/v1/portal/address" -Token $token -Body @{
    receiver = 'Pay Recv'; phone = '13400002222'; province = 'GD'; city = 'SZ'; district = 'NS'
    detail = 'Pay Road 1'; isDefault = 1
} -Name '08-addr'
$addrId = [long](Payload $addr)

function NewOrder([int]$qty) {
    Http-Json -Method POST -Url "$portal/api/v1/portal/cart" -Token $token -Body @{ skuId = $skuId; quantity = $qty } -Name ("10-cart-" + [guid]::NewGuid().ToString('N').Substring(0,6)) | Out-Null
    $o = Http-Json -Method POST -Url "$portal/api/v1/portal/order" -Token $token -Body @{ addressId = $addrId; requestId = ('pay-' + [guid]::NewGuid().ToString('N')) } -Name ("11-order-" + [guid]::NewGuid().ToString('N').Substring(0,6))
    return [long](Payload $o).orderId
}

$order1 = NewOrder 2
$order2 = NewOrder 1
Check 'setup: two unpaid orders created' (($order1 -gt 0) -and ($order2 -gt 0)) "orders=$order1,$order2"

# ============================================================ A) 渠道
Write-Host ''
Write-Host '== A) channels =='
$ch = Http-Json -Method GET -Url "$portal/api/v1/portal/payment/channels" -Token $token -Name '20-channels'
$codes = (Payload $ch) | ForEach-Object { $_.code }
Check 'A1 MOCK channel is available in dev' ($codes -contains 'MOCK') "codes=$($codes -join ',')"
Check 'A2 unconfigured channels are hidden (no alipay/wechat without credentials)' `
    (-not ($codes -contains 'ALIPAY_WAP') -and -not ($codes -contains 'WECHAT_NATIVE')) "codes=$($codes -join ',')"

# ============================================================ B) 创建支付单
Write-Host ''
Write-Host '== B) create payment =='
$p1 = Http-Json -Method POST -Url "$portal/api/v1/portal/payment" -Token $token -Body @{ orderId = $order1; channel = 'MOCK' } -Name '30-create'
$payNo1 = (Payload $p1).paymentNo
Check 'B1 create succeeds' ((Code $p1) -eq 200 -and $payNo1) "code=$(Code $p1) paymentNo=$payNo1"
Check 'B2 amount equals order payAmount (100.00)' ((Payload $p1).amount -eq 100) "amount=$((Payload $p1).amount)"
Check 'B3 status is PENDING(0) and mockUrl returned' `
    ((Payload $p1).status -eq 0 -and (Payload $p1).payParams.mockUrl) "status=$((Payload $p1).status) mockUrl=$((Payload $p1).payParams.mockUrl)"

$b4 = Http-Json -Method POST -Url "$portal/api/v1/portal/payment" -Token $otherToken -Body @{ orderId = $order1; channel = 'MOCK' } -Name '31-create-others'
Check 'B4 creating a payment for someone else order is rejected (3001)' ((Code $b4) -eq 3001) "code=$(Code $b4)"

$b5 = Http-Json -Method POST -Url "$portal/api/v1/portal/payment" -Token $token -Body @{ orderId = $order1; channel = 'ALIPAY_WAP' } -Name '32-create-alipay'
Check 'B5 unconfigured channel is rejected (4001)' ((Code $b5) -eq 4001) "code=$(Code $b5) msg=$(Payload $b5)"

$b6 = Http-Json -Method POST -Url "$portal/api/v1/portal/payment" -Token $token -Body @{ orderId = $order1; channel = 'NOPE' } -Name '33-create-badchannel'
Check 'B6 unknown channel is rejected (400)' ((Code $b6) -eq 400) "code=$(Code $b6)"

# ============================================================ C) 支付
Write-Host ''
Write-Host '== C) pay (mock) =='
$stockBefore = [int](Sql "SELECT stock FROM t_sku WHERE id = $skuId;")
$salesBefore = [int](Sql "SELECT sales FROM t_product WHERE id = $prodId;")

$c1 = Http-Json -Method POST -Url "$portal/api/v1/portal/payment/$payNo1/mock-pay" -Token $token -Name '40-mock-pay'
Check 'C1 mock pay succeeds' ((Code $c1) -eq 200) "code=$(Code $c1)"
Check 'C2 order moved to PAID(1)' ((Sql "SELECT status FROM t_order WHERE id = $order1;") -eq '1') "status=$(Sql "SELECT status FROM t_order WHERE id = $order1;")"

$payRow = Sql "SELECT CONCAT(status,'|',IFNULL(channel_trade_no,'-')) FROM t_payment WHERE payment_no = '$payNo1';"
Check 'C3 payment row: PAID(1) and channel trade no recorded' ($payRow -like '1|*') "row=$payRow"

$stockAfter = [int](Sql "SELECT stock FROM t_sku WHERE id = $skuId;")
$salesAfter = [int](Sql "SELECT sales FROM t_product WHERE id = $prodId;")
Check 'C4 stock deducted exactly once (-2)' (($stockBefore - $stockAfter) -eq 2) "stock $stockBefore -> $stockAfter"
Check 'C5 sales increased exactly once (+2)' (($salesAfter - $salesBefore) -eq 2) "sales $salesBefore -> $salesAfter"

# 重复支付：必须幂等，不能再扣一次库存
$c6 = Http-Json -Method POST -Url "$portal/api/v1/portal/payment/$payNo1/mock-pay" -Token $token -Name '41-mock-pay-again'
$stockAfter2 = [int](Sql "SELECT stock FROM t_sku WHERE id = $skuId;")
Check 'C6 paying twice is idempotent (no extra stock deduction)' ($stockAfter2 -eq $stockAfter) "stock $stockAfter -> $stockAfter2"

$b7 = Http-Json -Method POST -Url "$portal/api/v1/portal/payment" -Token $token -Body @{ orderId = $order1; channel = 'MOCK' } -Name '42-create-paid'
Check 'C7 creating a payment for a paid order is rejected (3002)' ((Code $b7) -eq 3002) "code=$(Code $b7)"

# ============================================================ D) 新支付单会关掉旧的
Write-Host ''
Write-Host '== D) a new payment closes the pending one =='
$d1 = Http-Json -Method POST -Url "$portal/api/v1/portal/payment" -Token $token -Body @{ orderId = $order2; channel = 'MOCK' } -Name '50-create-a'
$payA = (Payload $d1).paymentNo
$d2 = Http-Json -Method POST -Url "$portal/api/v1/portal/payment" -Token $token -Body @{ orderId = $order2; channel = 'MOCK' } -Name '51-create-b'
$payB = (Payload $d2).paymentNo
$statusA = Sql "SELECT status FROM t_payment WHERE payment_no = '$payA';"
$statusB = Sql "SELECT status FROM t_payment WHERE payment_no = '$payB';"
Check 'D1 the older pending payment was closed (2)' ($statusA -eq '2') "old=$statusA"
Check 'D2 the newest payment is still pending (0)' ($statusB -eq '0') "new=$statusB"

$d3 = Http-Json -Method POST -Url "$portal/api/v1/portal/payment/$payA/mock-pay" -Token $token -Name '52-pay-closed'
Check 'D3 a closed payment cannot be paid (4003)' ((Code $d3) -eq 4003) "code=$(Code $d3)"
Check 'D4 order is still unpaid after paying a closed payment' ((Sql "SELECT status FROM t_order WHERE id = $order2;") -eq '0') "status=$(Sql "SELECT status FROM t_order WHERE id = $order2;")"

# ============================================================ E) 回调
Write-Host ''
Write-Host '== E) channel notify =='
$e1 = Http-Json -Method POST -Url "$portal/api/v1/portal/pay/notify/ALIPAY_WAP" -Body @{ out_trade_no = $payB; trade_status = 'TRADE_SUCCESS'; total_amount = '50.00' } -Name '60-forged-notify'
Check 'E1 forged alipay notify is rejected (not configured / no signature)' ((Code $e1) -in @(4001, 4005) -or $e1.http -ge 400) "code=$(Code $e1) http=$($e1.http)"
Check 'E2 order untouched by the forged notify' ((Sql "SELECT status FROM t_order WHERE id = $order2;") -eq '0') "status=$(Sql "SELECT status FROM t_order WHERE id = $order2;")"

$e3 = Http-Json -Method POST -Url "$portal/api/v1/portal/pay/notify/MOCK" -Body @{ out_trade_no = 'P-NOT-EXIST' } -Name '61-notify-unknown'
Check 'E3 notify for an unknown payment is rejected' ((Code $e3) -ne 200) "code=$(Code $e3)"

# ============================================================ F) 越权与鉴权
Write-Host ''
Write-Host '== F) authorization =='
$f1 = Http-Json -Method GET -Url "$portal/api/v1/portal/payment/$payNo1" -Token $otherToken -Name '70-detail-others'
Check 'F1 reading someone else payment is rejected (4002)' ((Code $f1) -eq 4002) "code=$(Code $f1)"

$f2 = Http-Json -Method GET -Url "$portal/api/v1/portal/payment/channels" -Name '71-anon'
Check 'F2 anonymous cannot list channels (401)' ($f2.http -eq 401) "http=$($f2.http)"

$f3 = Http-Json -Method POST -Url "$portal/api/v1/portal/payment/$payB/mock-pay" -Token $otherToken -Name '72-pay-others'
Check 'F3 paying someone else payment is rejected (4002)' ((Code $f3) -eq 4002) "code=$(Code $f3)"

# ============================================================ G) 老接口兼容
Write-Host ''
Write-Host '== G) legacy /order/{id}/pay still works and creates a payment record =='
$g1 = Http-Json -Method POST -Url "$portal/api/v1/portal/order/$order2/pay" -Token $token -Name '80-legacy-pay'
Check 'G1 legacy pay endpoint succeeds' ((Code $g1) -eq 200) "code=$(Code $g1)"
Check 'G2 order is paid' ((Sql "SELECT status FROM t_order WHERE id = $order2;") -eq '1') "status=$(Sql "SELECT status FROM t_order WHERE id = $order2;")"
$legacyCount = [int](Sql "SELECT COUNT(*) FROM t_payment WHERE order_id = $order2 AND status = 1;")
Check 'G3 legacy pay also leaves a paid payment record (unified flow)' ($legacyCount -eq 1) "paidPaymentRows=$legacyCount"

# ============================================================ cleanup
Write-Host ''
Write-Host '== cleanup =='
Purge
Check 'cleanup: test data removed' ([int](Sql "SELECT COUNT(*) FROM t_user WHERE username IN ('$BUYER','$OTHER');") -eq 0) 'leftover users = 0'

$fail = Save-Summary 'Payment flow verification'
exit $fail
