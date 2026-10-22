# ============================================================================
# 对账验证（支付 + 退款）
#
# 对账是资金链路的最后一道防线：单笔查单只能查"我们知道的那些"，对账能发现
# **我们根本不知道存在的那笔钱**。
#
# 这个脚本用归一化账单把支付与退款两侧的各类结果都造出来：
#   支付：matched / MISSING_LOCAL / AMOUNT_MISMATCH / MISSING_CHANNEL
#   退款：matched / MISSING_LOCAL / AMOUNT_MISMATCH / MISSING_CHANNEL
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
    Sql ("DELETE FROM t_after_sale WHERE user_id IN (SELECT id FROM t_user WHERE username = '$BUYER'); " +
        "DELETE FROM t_payment WHERE user_id IN (SELECT id FROM t_user WHERE username = '$BUYER'); " +
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

function NewRefund([long]$orderId) {
    $ap = Http-Json -Method POST -Url "$portal/api/v1/portal/after-sale" -Token $token -Body @{ orderId = $orderId; type = 1; reason = '对账测试' } -Name ("13-as-" + [guid]::NewGuid().ToString('N').Substring(0,6))
    $asId = [long](Payload $ap)
    Http-Json -Method POST -Url "$admin/api/v1/admin/after-sale/$asId/approve" -Token $adminToken -Body @{ remark = 'ok' } -Name ("14-approve-" + [guid]::NewGuid().ToString('N').Substring(0,6)) | Out-Null
    return $asId
}

$o1 = NewPaidOrder 1   # 30.00 -> 支付 matched
$o2 = NewPaidOrder 2   # 60.00 -> 支付金额不符（账单 55.00）
$o3 = NewPaidOrder 3   # 90.00 -> 支付侧账单里没有
$pay1 = Sql "SELECT payment_no FROM t_payment WHERE order_id = $o1 AND status = 1 ORDER BY id DESC LIMIT 1;"
$pay2 = Sql "SELECT payment_no FROM t_payment WHERE order_id = $o2 AND status = 1 ORDER BY id DESC LIMIT 1;"
$pay3 = Sql "SELECT payment_no FROM t_payment WHERE order_id = $o3 AND status = 1 ORDER BY id DESC LIMIT 1;"
Check 'setup: three paid orders with payment records' (($pay1 -like 'P*') -and ($pay2 -like 'P*') -and ($pay3 -like 'P*')) "p1=$pay1 p2=$pay2 p3=$pay3"

# 三笔退款：AS1 账单里金额一致、AS2 账单里金额不符、AS3 账单里没有
$as1 = NewRefund $o1
$as2 = NewRefund $o2
$as3 = NewRefund $o3
$rf1 = Sql "SELECT IFNULL(refund_no,'-') FROM t_after_sale WHERE id = $as1;"
$rf2 = Sql "SELECT IFNULL(refund_no,'-') FROM t_after_sale WHERE id = $as2;"
$rf3 = Sql "SELECT IFNULL(refund_no,'-') FROM t_after_sale WHERE id = $as3;"
Check 'setup: three approved refunds with refund_no' (($rf1 -like 'RFAS*') -and ($rf2 -like 'RFAS*') -and ($rf3 -like 'RFAS*')) "rf1=$rf1 rf2=$rf2 rf3=$rf3"

# ============================================================ A) 支付 + 退款 一起对
Write-Host ''
Write-Host '== A) reconcile payments and refunds =='
$billLines = @(
    'out_trade_no,channel_trade_no,amount,status,biz_type,refund_no',
    "$pay1,MOCKT-$STAMP-1,30.00,SUCCESS,PAY,",
    "$pay2,MOCKT-$STAMP-2,55.00,SUCCESS,PAY,",
    "GHOST-ORDER-$STAMP,MOCKT-$STAMP-9,12.34,SUCCESS,PAY,",
    "$pay1,MOCKR-$STAMP-1,30.00,SUCCESS,REFUND,$rf1",
    "$pay2,MOCKR-$STAMP-2,55.00,SUCCESS,REFUND,$rf2",
    "$pay1,MOCKR-$STAMP-9,12.34,SUCCESS,REFUND,RF-GHOST-$STAMP"
)
$content = $billLines -join "`n"

$rec = Http-Json -Method POST -Url "$admin/api/v1/admin/reconcile" -Token $adminToken -Body @{
    channel = 'MOCK'; billDate = $TODAY; content = $content
} -Name '20-reconcile'
$rv = Payload $rec
Check 'A1 reconcile succeeds' ((Code $rec) -eq 200 -and $rv.id) "code=$(Code $rec) batchId=$($rv.id)"
Check 'A2 totalLines = 6 (3 pay + 3 refund)' ($rv.totalLines -eq 6) "totalLines=$($rv.totalLines)"

# 支付侧
Check 'A3 pay matched = 1' ($rv.matched -eq 1) "matched=$($rv.matched)"
Check 'A4 pay missingLocal = 1 (channel has it, we do not)' ($rv.missingLocal -eq 1) "missingLocal=$($rv.missingLocal)"
Check 'A5 pay amountMismatch = 1' ($rv.amountMismatch -eq 1) "amountMismatch=$($rv.amountMismatch)"
# 当天库里还有别的测试订单（本地有、账单无），>=1 即可，B5 再精确校验我们自己那笔
Check 'A6 pay missingChannel >= 1' ($rv.missingChannel -ge 1) "missingChannel=$($rv.missingChannel)"

# 退款侧
Check 'A7 refundTotalLines = 3' ($rv.refundTotalLines -eq 3) "refundTotalLines=$($rv.refundTotalLines)"
Check 'A8 refund matched = 1' ($rv.refundMatched -eq 1) "refundMatched=$($rv.refundMatched)"
Check 'A9 refund missingLocal = 1 (channel refunded, we have no record)' ($rv.refundMissingLocal -eq 1) "refundMissingLocal=$($rv.refundMissingLocal)"
Check 'A10 refund amountMismatch = 1' ($rv.refundAmountMismatch -eq 1) "refundAmountMismatch=$($rv.refundAmountMismatch)"
Check 'A11 refund missingChannel >= 1 (local refund absent from bill)' ($rv.refundMissingChannel -ge 1) "refundMissingChannel=$($rv.refundMissingChannel)"
Check 'A12 batch marked as having differences (status=0)' ($rv.status -eq 0) "status=$($rv.status)"

# ============================================================ B) 差异明细
Write-Host ''
Write-Host '== B) diff details =='
$batchId = [long]$rv.id
$detail = Http-Json -Method GET -Url "$admin/api/v1/admin/reconcile/$batchId" -Token $adminToken -Name '30-detail'
$diffs = (Payload $detail).diffs
$payDiffs = $diffs | Where-Object { $_.bizType -eq 'PAY' }
$refundDiffs = $diffs | Where-Object { $_.bizType -eq 'REFUND' }

Check 'B1 pay diffs carry the three payment types' `
    ((($payDiffs | ForEach-Object { $_.diffType }) -join ',').Contains('MISSING_LOCAL') -and
     (($payDiffs | ForEach-Object { $_.diffType }) -join ',').Contains('AMOUNT_MISMATCH') -and
     (($payDiffs | ForEach-Object { $_.diffType }) -join ',').Contains('MISSING_CHANNEL')) `
    "payTypes=$(($payDiffs | ForEach-Object { $_.diffType }) -join ',')"

$ghost = $payDiffs | Where-Object { $_.diffType -eq 'MISSING_LOCAL' }
Check 'B2 missing-local points at the ghost order' ($ghost.paymentNo -eq "GHOST-ORDER-$STAMP") "paymentNo=$($ghost.paymentNo)"

$mismatch = $payDiffs | Where-Object { $_.diffType -eq 'AMOUNT_MISMATCH' }
Check 'B3 pay amount mismatch carries both amounts (60 vs 55)' ($mismatch.localAmount -eq 60 -and $mismatch.channelAmount -eq 55) "local=$($mismatch.localAmount) channel=$($mismatch.channelAmount)"

$refundGhost = $refundDiffs | Where-Object { $_.refundNo -eq "RF-GHOST-$STAMP" }
Check 'B4 refund ghost is classified as missing-local' ($refundGhost.diffType -eq 'MISSING_LOCAL') "type=$($refundGhost.diffType)"

$refundMismatch = $refundDiffs | Where-Object { $_.diffType -eq 'AMOUNT_MISMATCH' }
Check 'B5 refund mismatch carries both amounts (60 vs 55)' ($refundMismatch.localAmount -eq 60 -and $refundMismatch.channelAmount -eq 55) "local=$($refundMismatch.localAmount) channel=$($refundMismatch.channelAmount)"

$refundMissing = $refundDiffs | Where-Object { $_.diffType -eq 'MISSING_CHANNEL' }
Check 'B6 the omitted refund is reported as missing-channel' (($refundMissing.refundNo) -contains $rf3) "refundNos=$($refundMissing.refundNo -join ',')"

# ============================================================ C) 边界与权限
Write-Host ''
Write-Host '== C) edge cases and permissions =='
$page = Http-Json -Method GET -Url "$admin/api/v1/admin/reconcile/page?pageNum=1&pageSize=10" -Token $adminToken -Name '40-page'
Check 'C1 batch list contains the new batch' ((Payload $page).total -ge 1) "total=$((Payload $page).total)"

$empty = Http-Json -Method POST -Url "$admin/api/v1/admin/reconcile" -Token $adminToken -Body @{
    channel = 'MOCK'; billDate = $TODAY; content = 'garbage-not-a-bill'
} -Name '41-empty-bill'
Check 'C2 unparsable bill fails loudly (400), not a fake clean report' ((Code $empty) -eq 400) "code=$(Code $empty)"

$badChannel = Http-Json -Method POST -Url "$admin/api/v1/admin/reconcile" -Token $adminToken -Body @{
    channel = 'PAYPAL'; billDate = $TODAY; content = $content
} -Name '42-bad-channel'
Check 'C3 unknown channel is rejected (400)' ((Code $badChannel) -eq 400) "code=$(Code $badChannel)"

$userCall = Http-Json -Method GET -Url "$admin/api/v1/admin/reconcile/page" -Token $token -Name '43-user'
Check 'C4 normal user cannot read reconcile batches (403)' ($userCall.http -eq 403) "http=$($userCall.http)"

# ============================================================ D) 全部一致时不出差异
Write-Host ''
Write-Host '== D) a fully matching bill reports no diff =='
$cleanLines = @(
    'out_trade_no,channel_trade_no,amount,status,biz_type,refund_no',
    "$pay1,MOCKT-$STAMP-1,30.00,SUCCESS,PAY,",
    "$pay1,MOCKR-$STAMP-1,30.00,SUCCESS,REFUND,$rf1"
)
# 注意：这个账单只覆盖一笔支付 + 一笔退款，其它本地单仍会算 missingChannel，
# 所以这里只验"这两笔各自 matched"，不验整批 status。
$clean = Http-Json -Method POST -Url "$admin/api/v1/admin/reconcile" -Token $adminToken -Body @{
    channel = 'MOCK'; billDate = $TODAY; content = ($cleanLines -join "`n")
} -Name '50-clean'
$cv = Payload $clean
Check 'D1 single-payment bill matches on both sides' ($cv.matched -eq 1 -and $cv.refundMatched -eq 1) "matched=$($cv.matched) refundMatched=$($cv.refundMatched)"

# ============================================================ cleanup
Write-Host ''
Write-Host '== cleanup =='
Sql "DELETE FROM t_reconcile_diff WHERE batch_id > $maxBatch; DELETE FROM t_reconcile_batch WHERE id > $maxBatch;" | Out-Null
Purge
Check 'cleanup: test data removed' ([int](Sql "SELECT COUNT(*) FROM t_reconcile_batch WHERE id > $maxBatch;") -eq 0) 'leftover batches = 0'

$fail = Save-Summary 'Reconcile verification'
exit $fail
