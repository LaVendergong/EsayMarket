# ============================================================================
# 对账验证（渠道账单 vs 本地支付记录）
#
# 对账是资金链路的最后一道防线：单笔查单只能查"我们知道的那些"，对账能发现
# **我们根本不知道存在的那笔钱**。这个脚本用归一化账单把四类结果都造出来：
#   matched           两边都有、金额一致
#   MISSING_LOCAL     渠道有、本地没有（最危险）
#   AMOUNT_MISMATCH   两边都有但金额不同
#   MISSING_CHANNEL   本地有、账单里没有
#
# ASCII-only on purpose (see docs/AI-HANDOFF.md section 8, item 28).
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File D:\projects\mall\scripts\reconcile-verify.ps1
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
$outDir = Join-Path $root 'tmp\reconcile'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$reqFile = Join-Path $outDir '_request.json'
$script:results = New-Object System.Collections.Generic.List[object]
. (Join-Path $PSScriptRoot 'e2e-lib.ps1')

$STAMP = (Get-Date -Format 'HHmmss')
$BUYER = "rec$STAMP"
$PWD_OK = 'rec123456'
$CAT = "Rec Cat $STAMP"
$ITEM = "Rec Item $STAMP"
$SPEC = "REC-SPEC-$STAMP"
$PRICE = 30
$TODAY = (Get-Date -Format 'yyyy-MM-dd')

function Sql([string]$q) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $mysql -uroot "-p$MysqlPassword" -N --default-character-set=utf8mb4 -e "USE mall; $q" 2>$null
        return ($out | Out-String).Trim()
    } finally { $ErrorActionPreference = $prev }
}

function Purge {
    Sql ("DELETE FROM t_payment WHERE user_id IN (SELECT id FROM t_user WHERE username = '$BUYER'); " +
        "DELETE FROM t_order_item WHERE order_id IN (SELECT id FROM t_order WHERE user_id IN (SELECT id FROM t_user WHERE username = '$BUYER')); " +
        "DELETE FROM t_order WHERE user_id IN (SELECT id FROM t_user WHERE username = '$BUYER'); " +
        "DELETE FROM t_cart WHERE user_id IN (SELECT id FROM t_user WHERE username = '$BUYER'); " +
        "DELETE FROM t_address WHERE user_id IN (SELECT id FROM t_user WHERE username = '$BUYER'); " +
        "DELETE FROM t_user WHERE username = '$BUYER'; " +
        "DELETE s FROM t_sku s JOIN t_product p ON s.product_id = p.id WHERE p.name = '$ITEM'; " +
        "DELETE FROM t_product WHERE name = '$ITEM'; " +
        "DELETE FROM t_category WHERE name = '$CAT';") | Out-Null
}

# --------------------------------------------------------------------------- setup
Write-Host '== setup =='
Stop-MallApps | Out-Null
if (-not (Start-MallApps 'reconcile')) {
    Check 'setup: both apps started' $false 'see logs'
    $fail = Save-Summary 'Reconcile verification'
    exit $fail
}
Check 'setup: both apps started' $true 'logs'

Purge
$maxBatch = [int](Sql "SELECT IFNULL(MAX(id),0) FROM t_reconcile_batch;")

$adm = Http-Json -Method POST -Url "$admin/api/v1/admin/auth/login" -Body @{ username = 'malladmin'; password = 'admin123456' } -Name '01-admin'
$adminToken = (Payload $adm).token
$cat = Http-Json -Method POST -Url "$admin/api/v1/admin/category" -Token $adminToken -Body @{ parentId = 0; name = $CAT; sort = 99 } -Name '02-cat'
$prod = Http-Json -Method POST -Url "$admin/api/v1/admin/product" -Token $adminToken -Body @{
    categoryId = (Payload $cat); name = $ITEM; subTitle = 'reconcile'; price = $PRICE
    skus = @( @{ skuCode = $SPEC; spec = $SPEC; price = $PRICE; stock = 200 } )
} -Name '03-product'
$prodId = [long](Payload $prod)
$skuId = [long](Sql "SELECT id FROM t_sku WHERE product_id = $prodId LIMIT 1;")
Check 'setup: product/sku ready' ($skuId -gt 0) "skuId=$skuId"

Http-Json -Method POST -Url "$portal/api/v1/portal/auth/register" -Body @{ username = $BUYER; password = $PWD_OK; nickname = 'R' } -Name '04-reg' | Out-Null
$login = Http-Json -Method POST -Url "$portal/api/v1/portal/auth/login" -Body @{ username = $BUYER; password = $PWD_OK } -Name '05-login'
$token = (Payload $login).token
$addr = Http-Json -Method POST -Url "$portal/api/v1/portal/address" -Token $token -Body @{
    receiver = 'Rec Recv'; phone = '13300003333'; province = 'GD'; city = 'SZ'; district = 'NS'
    detail = 'Rec Road 1'; isDefault = 1
} -Name '06-addr'
$addrId = [long](Payload $addr)

function NewPaidOrder([int]$qty) {
    Http-Json -Method POST -Url "$portal/api/v1/portal/cart" -Token $token -Body @{ skuId = $skuId; quantity = $qty } -Name ("10-cart-" + [guid]::NewGuid().ToString('N').Substring(0,6)) | Out-Null
    $o = Http-Json -Method POST -Url "$portal/api/v1/portal/order" -Token $token -Body @{ addressId = $addrId; requestId = ('rec-' + [guid]::NewGuid().ToString('N')) } -Name ("11-order-" + [guid]::NewGuid().ToString('N').Substring(0,6))
    $id = [long](Payload $o).orderId
    Http-Json -Method POST -Url "$portal/api/v1/portal/order/$id/pay" -Token $token -Name '12-pay' | Out-Null
    return $id
}

$o1 = NewPaidOrder 1   # 30.00 -> matched
$o2 = NewPaidOrder 2   # 60.00 -> amount mismatch (bill says 55.00)
$o3 = NewPaidOrder 3   # 90.00 -> missing channel (not in bill)
$pay1 = Sql "SELECT payment_no FROM t_payment WHERE order_id = $o1 AND status = 1 ORDER BY id DESC LIMIT 1;"
$pay2 = Sql "SELECT payment_no FROM t_payment WHERE order_id = $o2 AND status = 1 ORDER BY id DESC LIMIT 1;"
$pay3 = Sql "SELECT payment_no FROM t_payment WHERE order_id = $o3 AND status = 1 ORDER BY id DESC LIMIT 1;"
Check 'setup: three paid orders with payment records' (($pay1 -like 'P*') -and ($pay2 -like 'P*') -and ($pay3 -like 'P*')) "p1=$pay1 p2=$pay2 p3=$pay3"

# ============================================================ A) 对账
Write-Host ''
Write-Host '== A) reconcile bill against local payments =='
$billLines = @(
    'out_trade_no,channel_trade_no,amount,status,biz_type',
    "$pay1,MOCKT-$STAMP-1,30.00,SUCCESS,PAY",
    "$pay2,MOCKT-$STAMP-2,55.00,SUCCESS,PAY",
    "GHOST-ORDER-$STAMP,MOCKT-$STAMP-9,12.34,SUCCESS,PAY"
)
$content = $billLines -join "`n"

$rec = Http-Json -Method POST -Url "$admin/api/v1/admin/reconcile" -Token $adminToken -Body @{
    channel = 'MOCK'; billDate = $TODAY; content = $content
} -Name '20-reconcile'
$rv = Payload $rec
Check 'A1 reconcile succeeds' ((Code $rec) -eq 200 -and $rv.id) "code=$(Code $rec) batchId=$($rv.id)"
Check 'A2 totalLines = 3' ($rv.totalLines -eq 3) "totalLines=$($rv.totalLines)"
Check 'A3 matched = 1' ($rv.matched -eq 1) "matched=$($rv.matched)"
Check 'A4 missingLocal = 1 (channel has it, we do not)' ($rv.missingLocal -eq 1) "missingLocal=$($rv.missingLocal)"
Check 'A5 amountMismatch = 1' ($rv.amountMismatch -eq 1) "amountMismatch=$($rv.amountMismatch)"
# 注意：不能断言"恰好 1 条"——当天库里还有其它测试/演示订单（本地有、账单里没有），
# 它们被算进 MISSING_CHANNEL 是**正确行为**。这里只要求 >= 1，并在 B5 精确校验我们自己那笔。
Check 'A6 missingChannel >= 1 (local payments absent from the bill)' ($rv.missingChannel -ge 1) "missingChannel=$($rv.missingChannel)"
Check 'A7 batch marked as having differences (status=0)' ($rv.status -eq 0) "status=$($rv.status)"

# ============================================================ B) 差异明细
Write-Host ''
Write-Host '== B) diff details =='
$batchId = [long]$rv.id
$detail = Http-Json -Method GET -Url "$admin/api/v1/admin/reconcile/$batchId" -Token $adminToken -Name '30-detail'
$diffs = (Payload $detail).diffs
$types = ($diffs | ForEach-Object { $_.diffType }) -join ','
Check 'B1 detail returns 2 + missingChannel diffs' ($diffs.Count -eq (2 + $rv.missingChannel)) "count=$($diffs.Count) expected=$(2 + $rv.missingChannel)"
Check 'B2 all three diff types present' ($types -like '*MISSING_LOCAL*' -and $types -like '*AMOUNT_MISMATCH*' -and $types -like '*MISSING_CHANNEL*') "types=$types"
Check 'B2b the ghost order is classified as missing-local, not missing-channel' (($diffs | Where-Object { $_.paymentNo -eq "GHOST-ORDER-$STAMP" }).diffType -eq 'MISSING_LOCAL') "ghostType=$(($diffs | Where-Object { $_.paymentNo -eq "GHOST-ORDER-$STAMP" }).diffType)"

$ghost = $diffs | Where-Object { $_.diffType -eq 'MISSING_LOCAL' }
Check 'B3 missing-local points at the ghost order' ($ghost.paymentNo -eq "GHOST-ORDER-$STAMP") "paymentNo=$($ghost.paymentNo)"

$mismatch = $diffs | Where-Object { $_.diffType -eq 'AMOUNT_MISMATCH' }
Check 'B4 amount mismatch carries both amounts (60 vs 55)' ($mismatch.localAmount -eq 60 -and $mismatch.channelAmount -eq 55) "local=$($mismatch.localAmount) channel=$($mismatch.channelAmount)"

$missingChannel = $diffs | Where-Object { $_.diffType -eq 'MISSING_CHANNEL' }
Check 'B5 missing-channel points at the omission' ($missingChannel.paymentNo -eq $pay3) "paymentNo=$($missingChannel.paymentNo)"

# ============================================================ C) 边界与权限
Write-Host ''
Write-Host '== C) edge cases and permissions =='
$page = Http-Json -Method GET -Url "$admin/api/v1/admin/reconcile/page?pageNum=1&pageSize=10" -Token $adminToken -Name '40-page'
Check 'C1 batch list contains the new batch' ((Payload $page).total -ge 1) "total=$((Payload $page).total)"

$empty = Http-Json -Method POST -Url "$admin/api/v1/admin/reconcile" -Token $adminToken -Body @{
    channel = 'MOCK'; billDate = $TODAY; content = 'garbage-not-a-bill'
} -Name '41-empty-bill'
Check 'C2 unparsable bill fails loudly (400), not a fake clean report' ((Code $empty) -eq 400) "code=$(Code $empty) msg=$(Payload $empty)"

$badChannel = Http-Json -Method POST -Url "$admin/api/v1/admin/reconcile" -Token $adminToken -Body @{
    channel = 'PAYPAL'; billDate = $TODAY; content = $content
} -Name '42-bad-channel'
Check 'C3 unknown channel is rejected (400)' ((Code $badChannel) -eq 400) "code=$(Code $badChannel)"

$userCall = Http-Json -Method GET -Url "$admin/api/v1/admin/reconcile/page" -Token $token -Name '43-user'
Check 'C4 normal user cannot read reconcile batches (403)' ($userCall.http -eq 403) "http=$($userCall.http)"

# ============================================================ cleanup
Write-Host ''
Write-Host '== cleanup =='
Sql "DELETE FROM t_reconcile_diff WHERE batch_id > $maxBatch; DELETE FROM t_reconcile_batch WHERE id > $maxBatch;" | Out-Null
Purge
Check 'cleanup: test data removed' ([int](Sql "SELECT COUNT(*) FROM t_reconcile_batch WHERE id > $maxBatch;") -eq 0) 'leftover batches = 0'

$fail = Save-Summary 'Reconcile verification'
exit $fail
