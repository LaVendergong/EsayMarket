# ============================================================================
# Virtual goods verification (card key / download link, no shipping)
#
# Covers:
#   - product types: VIRTUAL product without address, freight forced to 0
#   - card key pool: import -> sku.stock += n -> pay -> grant -> reveal (view_count)
#   - order goes UNPAID -> PAID -> FINISHED right after payment (no manual ship)
#   - link type: grant content is the configured url
#   - mixed cart (physical + virtual) is rejected at preview
#   - after-sale rules: return-refund rejected, refund-only rejected once revealed
#
# Prerequisites: mysql migrated to sql/V16__virtual_goods.sql, both apps running.
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File scripts\digital-verify.ps1
# ============================================================================
[CmdletBinding()]
param(
    [string]$Portal = 'http://localhost:8090/api/v1',
    [string]$Admin = 'http://localhost:8081/api/v1',
    [string]$AdminUser = 'malladmin',
    [string]$AdminPass = 'admin123456'
)

$ErrorActionPreference = 'Stop'
$outDir = Join-Path (Split-Path -Parent $PSScriptRoot) 'tmp\digital'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$reqFile = Join-Path $outDir '_request.json'
$respFile = Join-Path $outDir '_response.json'
$fails = 0
$tag = Get-Date -Format 'MMddHHmmss'
$user = "digi$tag"
$pass = 'digi123456'
# ASCII only: Windows PowerShell 5.1 reads a no-BOM UTF-8 script as ANSI,
# so any CJK literal here would be corrupted before it is ever sent to the API.
$catName = "DigiCat-$tag"
$cardName = "CardKey-$tag"
$linkName = "LinkItem-$tag"
$mixedName = "Physical-$tag"

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

# ---------------------------------------------------------------- admin setup
$at = (Api POST "$Admin/admin/auth/login" @{ username = $AdminUser; password = $AdminPass } $null).data.token
Step 'admin login' ($null -ne $at) 'token ok'

$cat = Api POST "$Admin/admin/category" @{ parentId = 0; name = $catName; sort = 99 } $at
$catId = $cat.data

# 卡密类虚拟商品：库存从 0 开始，靠导入卡密补
$cardProd = Api POST "$Admin/admin/product" @{
    categoryId = $catId; name = $cardName; price = 50; status = 1
    productType = 2; deliveryType = 1
    skus = @(@{ skuCode = "CK-$tag"; spec = '卡密'; price = 50; stock = 0 })
} $at
$cardProdId = $cardProd.data
Step 'create virtual card-key product' ($cardProd.code -eq 200 -and $cardProdId) "productId=$cardProdId code=$($cardProd.code)"

$detail = Api GET "$Admin/admin/product/$cardProdId" $null $at
$cardSkuId = $detail.data.skus[0].id
Step 'virtual product has no freight template' ($null -eq $detail.data.freightTemplateId) `
    "freightTemplateId=$($detail.data.freightTemplateId) productType=$($detail.data.productType)"

$import = Api POST "$Admin/admin/card-key/import" @{
    skuId = $cardSkuId
    lines = @("CARD-A-$tag,SECRET-A-$tag", "CARD-B-$tag,SECRET-B-$tag")
} $at
Step 'import 2 card keys' ($import.code -eq 200 -and $import.data -eq 2) "imported=$($import.data)"

$count = Api GET "$Admin/admin/card-key/count?skuId=$cardSkuId" $null $at
Step 'card key pool has 2 available' ($count.data -eq 2) "count=$($count.data)"
$afterImport = Api GET "$Admin/admin/product/$cardProdId" $null $at
Step 'sku stock increased by import' ($afterImport.data.skus[0].stock -eq 2) "stock=$($afterImport.data.skus[0].stock)"

# 外链类虚拟商品
$linkProd = Api POST "$Admin/admin/product" @{
    categoryId = $catId; name = $linkName; price = 30; status = 1
    productType = 2; deliveryType = 2; deliveryUrl = 'https://example.com/ebook.pdf'
    skus = @(@{ skuCode = "LK-$tag"; spec = '下载'; price = 30; stock = 99 })
} $at
$linkProdId = $linkProd.data
$linkSkuId = (Api GET "$Admin/admin/product/$linkProdId" $null $at).data.skus[0].id
Step 'create virtual link product' ($linkProd.code -eq 200 -and $linkProdId) "productId=$linkProdId"

# 实物商品，用于混单校验
$mixedProd = Api POST "$Admin/admin/product" @{
    categoryId = $catId; name = $mixedName; price = 10; status = 1
    productType = 1
    skus = @(@{ skuCode = "PH-$tag"; spec = '实物'; price = 10; stock = 99 })
} $at
$mixedProdId = $mixedProd.data
$mixedSkuId = (Api GET "$Admin/admin/product/$mixedProdId" $null $at).data.skus[0].id

# ---------------------------------------------------------------- buyer
Api POST "$Portal/portal/auth/register" @{ username = $user; password = $pass; nickname = $user } $null | Out-Null
$token = (Api POST "$Portal/portal/auth/login" @{ username = $user; password = $pass } $null).data.token
Step 'buyer login' ($null -ne $token) 'token ok'

$p = Api GET "$Portal/portal/product/$cardProdId" $null $null
Step 'detail marks product as virtual' ($p.data.productType -eq 2) "productType=$($p.data.productType)"
Step 'freight text present (virtual: no logistics)' ($p.data.freightText.Length -gt 0) "len=$($p.data.freightText.Length)"

# ---------------------------------------------------------------- buy card key (no address!)
$null = Api POST "$Portal/portal/cart" @{ skuId = $cardSkuId; quantity = 1 } $token
$null = Api PUT "$Portal/portal/cart/checked?checked=1" $null $token
$preview = Api POST "$Portal/portal/order/preview" @{} $token
Step 'preview: virtual order, zero freight' `
    (($preview.data.orderType -eq 2) -and ($preview.data.freightAmount -eq 0) -and ($preview.data.payAmount -eq 50)) `
    "orderType=$($preview.data.orderType) freight=$($preview.data.freightAmount) pay=$($preview.data.payAmount)"

$order = Api POST "$Portal/portal/order" @{ requestId = "digi-$tag" } $token
$orderId = $order.data.orderId
Step 'order placed without address' ($order.code -eq 200 -and $orderId) "orderId=$orderId code=$($order.code)"

$pay = Api POST "$Portal/portal/order/$orderId/pay" @{} $token
Step 'pay succeeds' ($pay.code -eq 200) "code=$($pay.code)"

$after = Api GET "$Portal/portal/order/$orderId" $null $token
Step 'virtual order goes straight to FINISHED(3)' ($after.data.status -eq 3) "status=$($after.data.status)"

# ---------------------------------------------------------------- grants
$grants = Api GET "$Portal/portal/digital/order/$orderId" $null $token
$gCount = @($grants.data).Count
Step 'grant issued right after payment' ($gCount -eq 1) "grants=$gCount"
$gid = @($grants.data)[0].id
Step 'grant carries no plaintext' (-not (@($grants.data)[0].PSObject.Properties.Name -contains 'content')) 'list has no content field'

$reveal = Api POST "$Portal/portal/digital/$gid/reveal" @{} $token
Step 'reveal returns card key' ($reveal.data.content -like "*SECRET-A-$tag*") "content=$($reveal.data.content)"
Step 'first reveal counts 1' ($reveal.data.viewCount -eq 1) "viewCount=$($reveal.data.viewCount)"

$reveal2 = Api POST "$Portal/portal/digital/$gid/reveal" @{} $token
Step 'second reveal counts 2' ($reveal2.data.viewCount -eq 2) "viewCount=$($reveal2.data.viewCount)"

$mine = Api GET "$Portal/portal/digital/mine" $null $token
Step 'my grants list' (@($mine.data).Count -ge 1) "count=$(@($mine.data).Count)"

# ---------------------------------------------------------------- after-sale rules
$returnRefund = Api POST "$Portal/portal/after-sale" @{
    orderId = $orderId; type = 2; reason = 'no longer needed'
} $token
Step 'return-refund rejected for virtual goods' ($returnRefund.code -ne 200) "code=$($returnRefund.code)"

$refundOnly = Api POST "$Portal/portal/after-sale" @{
    orderId = $orderId; type = 1; reason = 'no longer needed'
} $token
Step 'refund-only rejected once revealed' ($refundOnly.code -ne 200) "code=$($refundOnly.code)"

# ---------------------------------------------------------------- link product
$null = Api POST "$Portal/portal/cart" @{ skuId = $linkSkuId; quantity = 1 } $token
$null = Api PUT "$Portal/portal/cart/checked?checked=1" $null $token
$linkOrder = Api POST "$Portal/portal/order" @{ requestId = "digi-link-$tag" } $token
$linkOrderId = $linkOrder.data.orderId
$null = Api POST "$Portal/portal/order/$linkOrderId/pay" @{} $token
$linkGrants = Api GET "$Portal/portal/digital/order/$linkOrderId" $null $token
$linkGid = @($linkGrants.data)[0].id
$linkReveal = Api POST "$Portal/portal/digital/$linkGid/reveal" @{} $token
Step 'link grant returns the configured url' ($linkReveal.data.content -eq 'https://example.com/ebook.pdf') `
    "content=$($linkReveal.data.content)"
Step 'link order finished' ((Api GET "$Portal/portal/order/$linkOrderId" $null $token).data.status -eq 3) 'status=3'

# ---------------------------------------------------------------- mixed cart
# 必须真的把「实物 + 虚拟」同时放进购物车并勾选：上一单已清空购物车，只加一件是测不出混单的
$null = Api POST "$Portal/portal/cart" @{ skuId = $linkSkuId; quantity = 1 } $token
$null = Api POST "$Portal/portal/cart" @{ skuId = $mixedSkuId; quantity = 1 } $token
$null = Api PUT "$Portal/portal/cart/checked?checked=1" $null $token
$mixed = Api POST "$Portal/portal/order/preview" @{} $token
Step 'mixed physical + virtual is rejected (7101)' ($mixed.code -eq 7101) "code=$($mixed.code)"

$mixedOrder = Api POST "$Portal/portal/order" @{ requestId = "digi-mixed-$tag" } $token
Step 'mixed order is rejected too' ($mixedOrder.code -eq 7101) "code=$($mixedOrder.code)"

# ---------------------------------------------------------------- cleanup
# 演示库要能反复跑：把本次建的分类和商品删掉。删失败不让结果变红（不影响业务结论），
# 但分类必须删 —— parentId=0 的测试分类会直接出现在前台分类 tab 里。
$null = Api DELETE "$Admin/admin/product/$cardProdId" $null $at
$null = Api DELETE "$Admin/admin/product/$linkProdId" $null $at
$null = Api DELETE "$Admin/admin/product/$mixedProdId" $null $at
$catDeleted = Api DELETE "$Admin/admin/category/$catId" $null $at
Step 'cleanup: test category removed' ($catDeleted.code -eq 200) "code=$($catDeleted.code)"
if ($catDeleted.code -ne 200) {
    Write-Host ("  hint: " + $catDeleted.message)
}

Write-Host ''
Write-Host ("RESULT fails={0}" -f $fails)
if ($fails -gt 0) { exit 1 }
