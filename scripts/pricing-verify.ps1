# ============================================================================
# Pricing / coupon verification (server-side amounts, coupon lifecycle, admin CRUD)
#
# Covers:
#   - coupon center / receive / duplicate receive / my coupons
#   - preview: goods + freight - discount = pay, coupon never pays freight
#   - place order with userCouponId -> coupon locked (0 -> 1) -> cancel -> released (1 -> 0)
#   - per-item discount allocation (sum must equal the order discount)
#   - admin: coupon page/create/stop/delete guard, freight template guard, product binding
#
# Prerequisites:
#   - mysql running, migrations applied up to sql/V15__coupon_seed.sql
#   - both apps started (mall-admin :8081, mall-portal :8090)
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\pricing-verify.ps1
#   optional: -Portal http://localhost:8090/api/v1 -Admin http://localhost:8081/api/v1
#
# Notes (why curl instead of Invoke-RestMethod):
#   Windows PowerShell 5.1 decodes responses as Latin-1 and strips quotes from -d '{...}',
#   which corrupts Chinese payloads both ways. curl + files keeps the bytes intact.
# ============================================================================
[CmdletBinding()]
param(
    [string]$Portal = 'http://localhost:8090/api/v1',
    [string]$Admin = 'http://localhost:8081/api/v1',
    [string]$AdminUser = 'malladmin',
    [string]$AdminPass = 'admin123456'
)

$ErrorActionPreference = 'Stop'
$script:outDir = Join-Path (Split-Path -Parent $PSScriptRoot) 'tmp\pricing'
New-Item -ItemType Directory -Force -Path $script:outDir | Out-Null
$script:reqFile = Join-Path $script:outDir '_request.json'
$script:respFile = Join-Path $script:outDir '_response.json'
$script:fails = 0
$script:tag = Get-Date -Format 'MMddHHmmss'
$script:user = "pricing$($script:tag)"
$script:pass = 'pricing123456'

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
        [System.IO.File]::WriteAllText($script:reqFile, ($Body | ConvertTo-Json -Depth 10),
            (New-Object System.Text.UTF8Encoding($false)))
        $args += @('--data-binary', "@$($script:reqFile)")
    }
    Remove-Item $script:respFile -Force -ErrorAction SilentlyContinue
    $args += @('-o', $script:respFile)
    & curl.exe @args | Out-Null
    $txt = if (Test-Path $script:respFile) { Get-Content $script:respFile -Raw -Encoding UTF8 } else { '' }
    if (-not $txt) { return [pscustomobject]@{ code = -1; message = 'empty response' } }
    try { return ($txt | ConvertFrom-Json) } catch { return [pscustomobject]@{ code = -1; message = $txt } }
}

# ---------------------------------------------------------------- reachability
try {
    $null = Invoke-WebRequest -Uri "$Portal/portal/category/tree" -UseBasicParsing -TimeoutSec 5
} catch {
    Write-Host "portal is not reachable at $Portal - start the apps first (run.bat)"
    exit 2
}

# ---------------------------------------------------------------- user + coupon
Api POST "$Portal/portal/auth/register" @{ username = $script:user; password = $script:pass; nickname = $script:user } $null | Out-Null
$login = Api POST "$Portal/portal/auth/login" @{ username = $script:user; password = $script:pass } $null
$token = $login.data.token
Step 'login' ($null -ne $token) "code=$($login.code)"

# center 需要登录（要算 receivedByMe）
$center = Api GET "$Portal/portal/coupon/center" $null $token
if (@($center.data).Count -lt 2) {
    Write-Host 'demo coupons missing - apply sql/V15__coupon_seed.sql first'
    exit 2
}

$certs = @($center.data)
$c100 = $certs | Where-Object { $_.threshold -eq 100 } | Select-Object -First 1
$c5000 = $certs | Where-Object { $_.threshold -eq 5000 } | Select-Object -First 1
Step 'center has demo coupons' ($null -ne $c100 -and $null -ne $c5000) "count=$($certs.Count)"

$r1 = Api POST "$Portal/portal/coupon/$($c100.id)/receive" @{} $token
Step 'receive coupon' ($r1.code -eq 200) "code=$($r1.code)"
$r2 = Api POST "$Portal/portal/coupon/$($c100.id)/receive" @{} $token
Step 'receive twice is rejected' ($r2.code -eq 7005) "code=$($r2.code)"
$r3 = Api POST "$Portal/portal/coupon/$($c5000.id)/receive" @{} $token
Step 'receive threshold-5000 coupon' ($r3.code -eq 200) "code=$($r3.code)"

$mine = Api GET "$Portal/portal/coupon/mine" $null $token
$uc = @($mine.data | Where-Object { $_.couponId -eq $c100.id }) | Select-Object -First 1
$ucId = $uc.id
Step 'my coupon is unused' ($uc.status -eq 0) "id=$ucId status=$($uc.status)"

# ---------------------------------------------------------------- preview
$null = Api POST "$Portal/portal/cart" @{ skuId = 5; quantity = 1 } $token
$null = Api PUT "$Portal/portal/cart/checked?checked=1" $null $token

$p1 = Api POST "$Portal/portal/order/preview" @{} $token
Step 'preview: 399 goods + 8 freight = 407' `
    (($p1.data.goodsAmount -eq 399) -and ($p1.data.freightAmount -eq 8) -and ($p1.data.payAmount -eq 407)) `
    "goods=$($p1.data.goodsAmount) freight=$($p1.data.freightAmount) pay=$($p1.data.payAmount)"
$bigCoupon = @($p1.data.coupons | Where-Object { $_.threshold -eq 5000 }) | Select-Object -First 1
Step 'preview marks coupon below threshold unusable' (($null -ne $bigCoupon) -and ($bigCoupon.usable -eq $false)) `
    "usable=$($bigCoupon.usable)"

$p2 = Api POST "$Portal/portal/order/preview" @{ userCouponId = $ucId } $token
Step 'preview with coupon: 407 - 20 = 387' `
    (($p2.data.discountAmount -eq 20) -and ($p2.data.payAmount -eq 387)) `
    "discount=$($p2.data.discountAmount) pay=$($p2.data.payAmount)"
Step 'coupon does not touch freight' ($p2.data.freightAmount -eq 8) "freight=$($p2.data.freightAmount)"

# ---------------------------------------------------------------- order + allocation
$addr = Api POST "$Portal/portal/address" @{
    receiver = 'Test'; phone = '13800000000'; province = 'GD'; city = 'SZ'
    district = 'NS'; detail = 'Keyuan 1'; isDefault = 1
} $token
$order = Api POST "$Portal/portal/order" @{
    addressId = $addr.data; requestId = "pricing-$($script:tag)"; userCouponId = $ucId
} $token
$oid = $order.data.orderId
Step 'order created' (($order.code -eq 200) -and $oid) "orderId=$oid code=$($order.code)"
Step 'order payAmount=387' ($order.data.payAmount -eq 387) "payAmount=$($order.data.payAmount)"

$detail = Api GET "$Portal/portal/order/$oid" $null $token
Step 'order keeps freight/discount snapshot' `
    (($detail.data.freightAmount -eq 8) -and ($detail.data.discountAmount -eq 20)) `
    "freight=$($detail.data.freightAmount) discount=$($detail.data.discountAmount)"

$items = @($detail.data.items)
$sumDiscount = ($items | Measure-Object -Property discountAmount -Sum).Sum
$sumPay = ($items | Measure-Object -Property payAmount -Sum).Sum
Step 'item discount allocates to the order discount' ($sumDiscount -eq 20) "sum=$sumDiscount"
Step 'item pay + freight equals order pay' (($sumPay + 8) -eq 387) "sumItemPay=$sumPay"

$locked = Api GET "$Portal/portal/coupon/mine" $null $token
$after = @($locked.data | Where-Object { $_.id -eq $ucId }) | Select-Object -First 1
Step 'coupon locked by order' (($after.status -eq 1) -and ($after.orderId -eq $oid)) `
    "status=$($after.status) orderId=$($after.orderId)"

$cancel = Api POST "$Portal/portal/order/$oid/cancel" @{} $token
Step 'cancel order' ($cancel.code -eq 200) "code=$($cancel.code)"
$released = Api GET "$Portal/portal/coupon/mine" $null $token
$back = @($released.data | Where-Object { $_.id -eq $ucId }) | Select-Object -First 1
Step 'cancel releases coupon' (($back.status -eq 0) -and ($null -eq $back.orderId)) `
    "status=$($back.status) orderId=$($back.orderId)"

# ---------------------------------------------------------------- admin
$adminLogin = Api POST "$Admin/admin/auth/login" @{ username = $AdminUser; password = $AdminPass } $null
$at = $adminLogin.data.token
Step 'admin login' ($null -ne $at) "code=$($adminLogin.code)"

$page = Api GET "$Admin/admin/coupon/page?pageNum=1&pageSize=10" $null $at
Step 'admin coupon page' ($page.data.total -ge 2) "total=$($page.data.total)"

$created = Api POST "$Admin/admin/coupon" @{ name = 'verify coupon'; threshold = 50; amount = 5; total = 10; status = 1 } $at
$newId = $created.data
Step 'admin create coupon' (($created.code -eq 200) -and $newId) "id=$newId"

$null = Api PUT "$Admin/admin/coupon/$newId/status?status=0" $null $at
$reload = Api GET "$Admin/admin/coupon/page?pageNum=1&pageSize=50&keyword=verify" $null $at
$row = @($reload.data.list | Where-Object { $_.id -eq $newId }) | Select-Object -First 1
Step 'admin stop coupon persists' ($row.status -eq 0) "status=$($row.status)"

$removed = Api DELETE "$Admin/admin/coupon/$newId" $null $at
Step 'admin delete unreceived coupon' ($removed.code -eq 200) "code=$($removed.code)"

$refuse = Api DELETE "$Admin/admin/coupon/$($c100.id)" $null $at
Step 'received coupon cannot be deleted' ($refuse.code -ne 200) "code=$($refuse.code)"

$freight = Api GET "$Admin/admin/freight" $null $at
Step 'freight list' (@($freight.data).Count -ge 2) "count=$(@($freight.data).Count)"

$refuseF = Api DELETE "$Admin/admin/freight/1" $null $at
Step 'bound freight template cannot be deleted' ($refuseF.code -ne 200) "code=$($refuseF.code)"

$p = Api GET "$Admin/admin/product/4" $null $at
Step 'product detail exposes freightTemplateId' ($null -ne $p.data.freightTemplateId) `
    "id=$($p.data.freightTemplateId)"

function ProductBody($d, $templateId) {
    return @{
        categoryId = $d.categoryId; name = $d.name; subTitle = $d.subTitle; mainImage = $d.mainImage
        images = @($d.images); detail = $d.detail; price = $d.price; status = $d.status
        freightTemplateId = $templateId
        skus = @($d.skus | ForEach-Object {
            @{ id = $_.id; skuCode = $_.skuCode; spec = $_.spec; image = $_.image; price = $_.price; stock = $_.stock }
        })
    }
}
$original = $p.data.freightTemplateId
$up = Api PUT "$Admin/admin/product/4" (ProductBody $p.data 2) $at
Step 'rebind product to template 2' ($up.code -eq 200) "code=$($up.code)"
$check = Api GET "$Admin/admin/product/4" $null $at
Step 'rebind persisted' ($check.data.freightTemplateId -eq 2) "id=$($check.data.freightTemplateId)"
$null = Api PUT "$Admin/admin/product/4" (ProductBody $check.data $original) $at

$bad = Api PUT "$Admin/admin/product/4" (ProductBody $check.data 9999) $at
Step 'unknown freight template is rejected' ($bad.code -eq 7006) "code=$($bad.code)"

# ---------------------------------------------------------------- expiration (0 -> 2)
# 把券模板的有效期改到过去，验证「我的券」不再谎报未使用，且结算页拿不到它。
# 全程走后台接口改，不依赖 mysql 客户端；无论成败都恢复原值。
$allCoupons = Api GET "$Admin/admin/coupon/page?pageNum=1&pageSize=50" $null $at
$target = @($allCoupons.data.list | Where-Object { $_.id -eq $c100.id }) | Select-Object -First 1
function CouponBody($row, $validTo) {
    return @{
        name = $row.name; threshold = $row.threshold; amount = $row.amount; total = $row.total
        validFrom = $row.validFrom; validTo = $validTo; status = $row.status
    }
}
try {
    $null = Api PUT "$Admin/admin/coupon/$($target.id)" `
        (CouponBody $target (Get-Date).AddDays(-1).ToString('yyyy-MM-ddTHH:mm:ss')) $at
    $mineExpired = Api GET "$Portal/portal/coupon/mine" $null $token
    $exp = @($mineExpired.data | Where-Object { $_.id -eq $ucId }) | Select-Object -First 1
    Step 'expired coupon is reported as expired' ($exp.status -eq 2) "status=$($exp.status)"

    # 购物车在上面的下单里已被清空，这里用「立即购买」重新试算
    $useExpired = Api POST "$Portal/portal/order/preview" @{ skuId = 5; quantity = 1; userCouponId = $ucId } $token
    Step 'expired coupon cannot be used' ($useExpired.code -eq 7002) "code=$($useExpired.code)"
} finally {
    $null = Api PUT "$Admin/admin/coupon/$($target.id)" (CouponBody $target $null) $at
    $restored = Api GET "$Admin/admin/coupon/page?pageNum=1&pageSize=50" $null $at
    $back2 = @($restored.data.list | Where-Object { $_.id -eq $target.id }) | Select-Object -First 1
    Step 'coupon validity restored' ($null -eq $back2.validTo) "validTo=$($back2.validTo)"
}

Write-Host ''
Write-Host ("RESULT fails={0}" -f $script:fails)
if ($script:fails -gt 0) { exit 1 }
