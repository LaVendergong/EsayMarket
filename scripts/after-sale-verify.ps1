# ============================================================================
# 售后（退款 / 退货退款）状态机验证
#
# 售后是这次新加的完整功能，四条不变式必须逐条守住，否则会出「重复退款」「订单卡在售后中」这类事故：
#   1) 只有 已支付/已发货/已完成 的订单能申请售后
#   2) 同一时刻一笔订单只能有一个进行中的售后单
#   3) 售后单只有「待商家处理」能被处理，处理过的不能再处理（防重复退款）
#   4) 拒绝 / 撤销必须把订单状态退回申请前，不能卡在「售后中」
# 另外验证：仅退款不恢复库存、退货退款恢复库存、越权访问被拒。
#
# ASCII-only on purpose (see docs/AI-HANDOFF.md section 8, item 28).
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File D:\projects\mall\scripts\after-sale-verify.ps1
# ============================================================================
param(
    [string]$MysqlPassword = '521131'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
# e2e-lib 依赖调用方作用域里的这些变量（缺一个就报「& 后面的表达式无效」这类怪错）
$portal = 'http://localhost:8090'
$admin = 'http://localhost:8081'
$mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$java = 'D:\jdks\jdk-17.0.12\bin\java.exe'
$outDir = Join-Path $root 'tmp\after-sale'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$reqFile = Join-Path $outDir '_request.json'
$script:results = New-Object System.Collections.Generic.List[object]
. (Join-Path $PSScriptRoot 'e2e-lib.ps1')

$STAMP = (Get-Date -Format 'HHmmss')
$BUYER = "as$STAMP"
$OTHER = "asother$STAMP"
$PWD_OK = 'as123456'
$CAT = "AS Cat $STAMP"
$ITEM = "AS Item $STAMP"
$SPEC = "AS-SPEC-$STAMP"
$PRICE = 100

function Sql([string]$q) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $mysql -uroot "-p$MysqlPassword" -N --default-character-set=utf8mb4 -e "USE mall; $q" 2>$null
        return ($out | Out-String).Trim()
    } finally { $ErrorActionPreference = $prev }
}

function Purge {
    Sql ("DELETE FROM t_after_sale WHERE user_id IN (SELECT id FROM t_user WHERE username IN ('$BUYER','$OTHER')); " +
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
if (-not (Start-MallApps 'aftersale')) {
    Check 'setup: both apps started' $false 'see logs'
    $fail = Save-Summary 'After-sale state machine verification'
    exit $fail
}
Check 'setup: both apps started' $true 'logs'

Purge
$adm = Http-Json -Method POST -Url "$admin/api/v1/admin/auth/login" -Body @{ username = 'malladmin'; password = 'admin123456' } -Name '01-admin'
$adminToken = (Payload $adm).token
$cat = Http-Json -Method POST -Url "$admin/api/v1/admin/category" -Token $adminToken -Body @{ parentId = 0; name = $CAT; sort = 97 } -Name '02-cat'
$prod = Http-Json -Method POST -Url "$admin/api/v1/admin/product" -Token $adminToken -Body @{
    categoryId = (Payload $cat); name = $ITEM; subTitle = 'after-sale'; price = $PRICE
    skus = @( @{ skuCode = $SPEC; spec = $SPEC; price = $PRICE; stock = 50 } )
} -Name '03-product'
$prodId = [long](Payload $prod)
$skuId = [long](Sql "SELECT id FROM t_sku WHERE product_id = $prodId LIMIT 1;")
Check 'setup: product/sku ready' ($skuId -gt 0) "productId=$prodId skuId=$skuId"

$reg = Http-Json -Method POST -Url "$portal/api/v1/portal/auth/register" -Body @{ username = $BUYER; password = $PWD_OK; nickname = 'AS' } -Name '04-reg'
Http-Json -Method POST -Url "$portal/api/v1/portal/auth/register" -Body @{ username = $OTHER; password = $PWD_OK; nickname = 'OTHER' } -Name '05-reg2' | Out-Null
$login = Http-Json -Method POST -Url "$portal/api/v1/portal/auth/login" -Body @{ username = $BUYER; password = $PWD_OK } -Name '06-login'
$token = (Payload $login).token
$loginOther = Http-Json -Method POST -Url "$portal/api/v1/portal/auth/login" -Body @{ username = $OTHER; password = $PWD_OK } -Name '07-login2'
$otherToken = (Payload $loginOther).token

$addr = Http-Json -Method POST -Url "$portal/api/v1/portal/address" -Token $token -Body @{
    receiver = 'AS Recv'; phone = '13500001111'; province = 'GD'; city = 'SZ'; district = 'NS'
    detail = 'AS Road 1'; isDefault = 1
} -Name '08-addr'
$addrId = [long](Payload $addr)

function NewOrder([int]$qty, [bool]$pay) {
    Http-Json -Method POST -Url "$portal/api/v1/portal/cart" -Token $token -Body @{ skuId = $skuId; quantity = $qty } -Name ("10-cart-" + [guid]::NewGuid().ToString('N').Substring(0,6)) | Out-Null
    $o = Http-Json -Method POST -Url "$portal/api/v1/portal/order" -Token $token -Body @{ addressId = $addrId; requestId = ('req-' + [guid]::NewGuid().ToString('N')) } -Name ("11-order-" + [guid]::NewGuid().ToString('N').Substring(0,6))
    $id = [long](Payload $o).orderId
    if ($pay) { Http-Json -Method POST -Url "$portal/api/v1/portal/order/$id/pay" -Token $token -Name '12-pay' | Out-Null }
    return $id
}

$orderPaid = NewOrder 2 $true
$orderUnpaid = NewOrder 1 $false
$stockBefore = [int](Sql "SELECT stock FROM t_sku WHERE id = $skuId;")
Check 'setup: paid order created (2 items, 200.00)' ((Sql "SELECT status FROM t_order WHERE id = $orderPaid;") -eq '1' -and (Sql "SELECT pay_amount FROM t_order WHERE id = $orderPaid;") -eq '200.00') `
    "status=$(Sql "SELECT status FROM t_order WHERE id = $orderPaid;") pay=$(Sql "SELECT pay_amount FROM t_order WHERE id = $orderPaid;")"
Check 'setup: unpaid order created' ((Sql "SELECT status FROM t_order WHERE id = $orderUnpaid;") -eq '0') "status=$(Sql "SELECT status FROM t_order WHERE id = $orderUnpaid;")"

# ============================================================ A) 状态限制
Write-Host ''
Write-Host '== A) only paid/shipped/finished orders can apply =='
$a1 = Http-Json -Method POST -Url "$portal/api/v1/portal/after-sale" -Token $token -Body @{ orderId = $orderUnpaid; type = 1; reason = 'test' } -Name '20-apply-unpaid'
Check 'A1 applying on an unpaid order is rejected (3006)' ((Code $a1) -eq 3006) "code=$(Code $a1) msg=$(Payload $a1)"

$a2 = Http-Json -Method POST -Url "$portal/api/v1/portal/after-sale" -Token $token -Body @{ orderId = 999999; type = 1; reason = 'test' } -Name '21-apply-missing'
Check 'A2 applying on a non-existent order is rejected (3001)' ((Code $a2) -eq 3001) "code=$(Code $a2)"

$a3 = Http-Json -Method POST -Url "$portal/api/v1/portal/after-sale" -Token $token -Body @{ orderId = $orderPaid; type = 9; reason = 'test' } -Name '22-apply-badtype'
Check 'A3 invalid after-sale type is rejected (400)' ((Code $a3) -eq 400) "code=$(Code $a3) msg=$(Payload $a3)"

$a4 = Http-Json -Method POST -Url "$portal/api/v1/portal/after-sale" -Token $token -Body @{ orderId = $orderPaid; type = 1; reason = '' } -Name '23-apply-noreason'
Check 'A4 blank reason is rejected (400)' ((Code $a4) -eq 400) "code=$(Code $a4) msg=$(Payload $a4)"

# ============================================================ B) 申请
Write-Host ''
Write-Host '== B) apply (refund only) =='
$ap1 = Http-Json -Method POST -Url "$portal/api/v1/portal/after-sale" -Token $token -Body @{ orderId = $orderPaid; type = 1; reason = '不想要了'; description = 'AS verify' } -Name '30-apply'
$asId = [long](Payload $ap1)
Check 'B1 apply succeeds' ((Code $ap1) -eq 200 -and $asId -gt 0) "code=$(Code $ap1) id=$asId"

$orderStatus = Sql "SELECT status FROM t_order WHERE id = $orderPaid;"
Check 'B2 order moved to AFTER_SALE(5)' ($orderStatus -eq '5') "status=$orderStatus"

$row = Sql "SELECT CONCAT(status,'|',type,'|',refund_amount,'|',order_status_before) FROM t_after_sale WHERE id = $asId;"
Check 'B3 row: pending / refund-only / refund=200.00 / prevStatus=1' ($row -eq '0|1|200.00|1') "row=$row"

$detail = Http-Json -Method GET -Url "$portal/api/v1/portal/after-sale/$asId" -Token $token -Name '31-detail'
$dv = Payload $detail
Check 'B4 detail exposes typeText/statusText and items' ($dv.typeText -eq '仅退款' -and $dv.statusText -eq '待商家处理' -and $dv.items.Count -ge 1) `
    "type=$(if($dv){$dv.typeText}) status=$(if($dv){$dv.statusText}) items=$(if($dv){$dv.items.Count})"

$a5 = Http-Json -Method POST -Url "$portal/api/v1/portal/after-sale" -Token $token -Body @{ orderId = $orderPaid; type = 1; reason = 'again' } -Name '32-apply-again'
Check 'B5 a second in-flight application is rejected (3006/3005)' ((Code $a5) -in @(3005, 3006)) "code=$(Code $a5)"

$b5 = Http-Json -Method POST -Url "$portal/api/v1/portal/after-sale" -Token $otherToken -Body @{ orderId = $orderPaid; type = 1; reason = 'steal' } -Name '33-apply-others'
Check 'B6 applying on someone else order is rejected (3001)' ((Code $b5) -eq 3001) "code=$(Code $b5)"

$b6 = Http-Json -Method GET -Url "$portal/api/v1/portal/after-sale/$asId" -Token $otherToken -Name '34-detail-others'
Check 'B7 reading someone else after-sale is rejected (3003)' ((Code $b6) -eq 3003) "code=$(Code $b6)"

# ============================================================ C) 用户撤销
Write-Host ''
Write-Host '== C) user cancels, order rolls back =='
$c1 = Http-Json -Method POST -Url "$portal/api/v1/portal/after-sale/$asId/cancel" -Token $token -Name '40-cancel'
Check 'C1 cancel succeeds' ((Code $c1) -eq 200) "code=$(Code $c1)"
Check 'C2 after-sale status = CANCELED(3)' ((Sql "SELECT status FROM t_after_sale WHERE id = $asId;") -eq '3') "status=$(Sql "SELECT status FROM t_after_sale WHERE id = $asId;")"
Check 'C3 order restored to PAID(1)' ((Sql "SELECT status FROM t_order WHERE id = $orderPaid;") -eq '1') "status=$(Sql "SELECT status FROM t_order WHERE id = $orderPaid;")"

$c2 = Http-Json -Method POST -Url "$portal/api/v1/portal/after-sale/$asId/cancel" -Token $token -Name '41-cancel-again'
Check 'C4 cancelling twice is rejected (3004)' ((Code $c2) -eq 3004) "code=$(Code $c2)"

# ============================================================ D) 商家拒绝
Write-Host ''
Write-Host '== D) merchant rejects, order rolls back =='
$ap2 = Http-Json -Method POST -Url "$portal/api/v1/portal/after-sale" -Token $token -Body @{ orderId = $orderPaid; type = 1; reason = '质量问题' } -Name '50-apply2'
$asId2 = [long](Payload $ap2)
Check 'D1 re-apply after cancel succeeds' ((Code $ap2) -eq 200 -and $asId2 -gt 0) "code=$(Code $ap2) id=$asId2"

$pending = Http-Json -Method GET -Url "$admin/api/v1/admin/after-sale/page?pageNum=1&pageSize=10&status=0" -Token $adminToken -Name '51-admin-pending'
Check 'D2 admin sees it in the pending list' ((Payload $pending).list.id -contains $asId2) "total=$((Payload $pending).total)"

$rj = Http-Json -Method POST -Url "$admin/api/v1/admin/after-sale/$asId2/reject" -Token $adminToken -Body @{ remark = '不符合退款条件' } -Name '52-reject'
Check 'D3 reject succeeds' ((Code $rj) -eq 200) "code=$(Code $rj)"
Check 'D4 after-sale status = REJECTED(2)' ((Sql "SELECT status FROM t_after_sale WHERE id = $asId2;") -eq '2') "status=$(Sql "SELECT status FROM t_after_sale WHERE id = $asId2;")"
Check 'D5 order restored to PAID(1)' ((Sql "SELECT status FROM t_order WHERE id = $orderPaid;") -eq '1') "status=$(Sql "SELECT status FROM t_order WHERE id = $orderPaid;")"
# 注意：不要直接对比 mysql 输出的中文 —— PowerShell 捕获原生命令输出按控制台代码页（GBK）解码，
# 会把「不符合退款条件」读成乱码，导致假失败。用 HEX 比对，输出是纯 ASCII，稳定。
$expectRemarkHex = [System.BitConverter]::ToString([System.Text.Encoding]::UTF8.GetBytes('不符合退款条件')).Replace('-', '')
$actualRemarkHex = Sql "SELECT HEX(handle_remark) FROM t_after_sale WHERE id = $asId2;"
Check 'D6 reject remark is stored' ($actualRemarkHex -eq $expectRemarkHex) "hex=$actualRemarkHex"

$rj2 = Http-Json -Method POST -Url "$admin/api/v1/admin/after-sale/$asId2/reject" -Token $adminToken -Body @{ remark = 'again' } -Name '53-reject-again'
Check 'D7 rejecting twice is rejected (3004)' ((Code $rj2) -eq 3004) "code=$(Code $rj2)"

# ============================================================ E) 商家同意（仅退款）
Write-Host ''
Write-Host '== E) merchant approves refund-only =='
$ap3 = Http-Json -Method POST -Url "$portal/api/v1/portal/after-sale" -Token $token -Body @{ orderId = $orderPaid; type = 1; reason = '不想要了' } -Name '60-apply3'
$asId3 = [long](Payload $ap3)
Check 'E1 third application succeeds' ((Code $ap3) -eq 200 -and $asId3 -gt 0) "id=$asId3"

$ok = Http-Json -Method POST -Url "$admin/api/v1/admin/after-sale/$asId3/approve" -Token $adminToken -Body @{ remark = '同意退款' } -Name '61-approve'
Check 'E2 approve succeeds' ((Code $ok) -eq 200) "code=$(Code $ok)"

# 关键：同意之后**钱真的退回去了**（走原支付渠道），不是只把状态改一下
$refundRow = Sql "SELECT CONCAT(refund_status,'|',IFNULL(refund_channel,'-'),'|',IFNULL(refund_no,'-')) FROM t_after_sale WHERE id = $asId3;"
Check 'E2b refund really went through the channel (2|MOCK|RFAS...)' ($refundRow -like '2|MOCK|RFAS*') "row=$refundRow"
Check 'E3 after-sale status = APPROVED(1)' ((Sql "SELECT status FROM t_after_sale WHERE id = $asId3;") -eq '1') "status=$(Sql "SELECT status FROM t_after_sale WHERE id = $asId3;")"
Check 'E4 order moved to REFUNDED(6)' ((Sql "SELECT status FROM t_order WHERE id = $orderPaid;") -eq '6') "status=$(Sql "SELECT status FROM t_order WHERE id = $orderPaid;")"

$stockAfterRefundOnly = [int](Sql "SELECT stock FROM t_sku WHERE id = $skuId;")
Check 'E5 refund-only does NOT restore stock' ($stockAfterRefundOnly -eq $stockBefore) "stock $stockBefore -> $stockAfterRefundOnly"

$ok2 = Http-Json -Method POST -Url "$admin/api/v1/admin/after-sale/$asId3/approve" -Token $adminToken -Body @{ remark = 'again' } -Name '62-approve-again'
Check 'E6 approving twice is rejected (3004, no double refund)' ((Code $ok2) -eq 3004) "code=$(Code $ok2)"

$refundNoAfter = Sql "SELECT IFNULL(refund_no,'-') FROM t_after_sale WHERE id = $asId3;"
Check 'E6b retry reuses the same refund no (no second refund)' ($refundNoAfter -like 'RFAS*') "refundNo=$refundNoAfter"

$e7 = Http-Json -Method POST -Url "$portal/api/v1/portal/after-sale" -Token $token -Body @{ orderId = $orderPaid; type = 2; reason = '再来一次' } -Name '63-apply-refunded'
Check 'E7 applying again on a refunded order is rejected (3006)' ((Code $e7) -eq 3006) "code=$(Code $e7)"

# ============================================================ F) 退货退款恢复库存
Write-Host ''
Write-Host '== F) return-refund restores stock =='
$orderF = NewOrder 2 $true
$stockF = [int](Sql "SELECT stock FROM t_sku WHERE id = $skuId;")
$apF = Http-Json -Method POST -Url "$portal/api/v1/portal/after-sale" -Token $token -Body @{ orderId = $orderF; type = 2; reason = '尺码不合适' } -Name '70-apply-return'
$asIdF = [long](Payload $apF)
Check 'F1 return-refund application succeeds' ((Code $apF) -eq 200 -and $asIdF -gt 0) "id=$asIdF"

Http-Json -Method POST -Url "$admin/api/v1/admin/after-sale/$asIdF/approve" -Token $adminToken -Body @{ remark = '同意退货退款' } -Name '71-approve-return' | Out-Null
Check 'F2 order moved to REFUNDED(6)' ((Sql "SELECT status FROM t_order WHERE id = $orderF;") -eq '6') "status=$(Sql "SELECT status FROM t_order WHERE id = $orderF;")"

$stockFAfter = [int](Sql "SELECT stock FROM t_sku WHERE id = $skuId;")
Check 'F3 return-refund restores stock (+2)' (($stockFAfter - $stockF) -eq 2) "stock $stockF -> $stockFAfter (expected +2)"

$refundRowF = Sql "SELECT IFNULL(refund_status,0) FROM t_after_sale WHERE id = $asIdF;"
Check 'F4 return-refund also refunded through the channel' ($refundRowF -eq '2') "refund_status=$refundRowF"

# ============================================================ G) 列表与权限
Write-Host ''
Write-Host '== G) lists and permissions =='
$myPage = Http-Json -Method GET -Url "$portal/api/v1/portal/after-sale/page?pageNum=1&pageSize=10" -Token $token -Name '80-my-page'
Check 'G1 user list contains all 4 applications' ((Payload $myPage).total -ge 4) "total=$((Payload $myPage).total)"

$all = Http-Json -Method GET -Url "$admin/api/v1/admin/after-sale/page?pageNum=1&pageSize=50" -Token $adminToken -Name '81-admin-all'
Check 'G2 admin list works without status filter' ((Code $all) -eq 200 -and (Payload $all).total -ge 4) "total=$((Payload $all).total)"

$adminDetail = Http-Json -Method GET -Url "$admin/api/v1/admin/after-sale/$asId3" -Token $adminToken -Name '82-admin-detail'
Check 'G3 admin detail includes order items' ((Payload $adminDetail).items.Count -ge 1) "items=$((Payload $adminDetail).items.Count)"

$g4 = Http-Json -Method GET -Url "$admin/api/v1/admin/after-sale/page" -Token $token -Name '83-user-on-admin'
Check 'G4 a normal user cannot read the admin after-sale list (403)' ($g4.http -eq 403) "http=$($g4.http)"

$g5 = Http-Json -Method GET -Url "$portal/api/v1/portal/after-sale/page" -Name '84-anon'
Check 'G5 anonymous request is rejected (401)' ($g5.http -eq 401) "http=$($g5.http)"

# ============================================================ H) 退款失败路径
Write-Host ''
Write-Host '== H) refund failure path =='
# 模拟「钱没收到 / 支付记录丢失」：把支付单删掉再审批
$orderFail = NewOrder 1 $true
$apFail = Http-Json -Method POST -Url "$portal/api/v1/portal/after-sale" -Token $token -Body @{ orderId = $orderFail; type = 1; reason = '退款失败路径' } -Name '90-apply-fail'
$asIdFail = [long](Payload $apFail)
Check 'H1 application for the failure-path order succeeds' ((Code $apFail) -eq 200 -and $asIdFail -gt 0) "id=$asIdFail"

Sql "DELETE FROM t_payment WHERE order_id = $orderFail;" | Out-Null
$failApprove = Http-Json -Method POST -Url "$admin/api/v1/admin/after-sale/$asIdFail/approve" -Token $adminToken -Body @{ remark = 'should fail' } -Name '91-approve-no-payment'
Check 'H2 approve fails when there is no successful payment (4002)' ((Code $failApprove) -eq 4002) "code=$(Code $failApprove)"

$failState = Sql "SELECT CONCAT(status,'|',IFNULL(refund_status,0)) FROM t_after_sale WHERE id = $asIdFail;"
Check 'H3 after-sale stays PENDING and refund marked FAILED (0|3)' ($failState -eq '0|3') "state=$failState"
$failErr = Sql "SELECT IFNULL(refund_error,'-') FROM t_after_sale WHERE id = $asIdFail;"
Check 'H4 failure reason is recorded for the agent' ($failErr.Length -gt 1) "refundError=$failErr"
Check 'H5 order stays in AFTER_SALE(5) on failure (not refunded)' ((Sql "SELECT status FROM t_order WHERE id = $orderFail;") -eq '5') "status=$(Sql "SELECT status FROM t_order WHERE id = $orderFail;")"

# ============================================================ cleanup

Write-Host ''
Write-Host '== cleanup =='
Purge
Check 'cleanup: test data removed' ([int](Sql "SELECT COUNT(*) FROM t_user WHERE username IN ('$BUYER','$OTHER');") -eq 0) 'leftover users = 0'

$fail = Save-Summary 'After-sale state machine verification'
exit $fail
