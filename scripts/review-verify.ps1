# ============================================================================
# Product review verification
#
# Covers:
#   - only FINISHED orders can be reviewed (paid-but-not-confirmed is rejected)
#   - one review per order item (duplicate -> 7201)
#   - plaintext label vs anonymous label
#   - product rating / review_count refreshed after submit AND after hide/restore
#   - other users cannot review someone else's order item
#
# Prerequisites: mysql migrated to sql/V18__review.sql, both apps running.
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File scripts\review-verify.ps1
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
$outDir = Join-Path (Split-Path -Parent $PSScriptRoot) 'tmp\review'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$reqFile = Join-Path $outDir '_request.json'
$respFile = Join-Path $outDir '_response.json'
$fails = 0
$tag = Get-Date -Format 'MMddHHmmss'
$prodName = "ReviewItem-$tag"

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

# 专属测试商品：避免污染真实商品的评分
$prod = Api POST "$Admin/admin/product" @{
    categoryId = 2; name = $prodName; price = 99; status = 1; productType = 1
    skus = @(@{ skuCode = "RV-$tag"; spec = 'std'; price = 99; stock = 20 })
} $at
$prodId = $prod.data
$skuId = (Api GET "$Admin/admin/product/$prodId" $null $at).data.skus[0].id
Step 'create test product' ($prod.code -eq 200 -and $prodId) "productId=$prodId skuId=$skuId"

# ---------------------------------------------------------------- buyer 1
$user1 = "rv1$tag"
$pwd = 'rv12345678'
Api POST "$Portal/portal/auth/register" @{ username = $user1; password = $pwd; nickname = "Nick$tag" } $null | Out-Null
$t1 = (Api POST "$Portal/portal/auth/login" @{ username = $user1; password = $pwd } $null).data.token

$addr = Api POST "$Portal/portal/address" @{
    receiver = 'RV'; phone = '13900000000'; province = 'GD'; city = 'SZ'
    district = 'NS'; detail = 'Review Road 1'; isDefault = 1
} $t1
$addrId = $addr.data

$o1 = Api POST "$Portal/portal/order/direct" @{ skuId = $skuId; quantity = 1; addressId = $addrId; requestId = "rv1-$tag" } $t1
$order1 = $o1.data.orderId
$null = Api POST "$Portal/portal/order/$order1/pay" @{} $t1
$item1 = @((Api GET "$Portal/portal/order/$order1" $null $t1).data.items)[0].id

# 未完成（已支付未确认收货）不能评价
$tooEarly = Api POST "$Portal/portal/review" @{ orderItemId = $item1; rating = 5; content = 'too early' } $t1
Step 'review rejected before order finished' ($tooEarly.code -ne 200) "code=$($tooEarly.code)"

$null = Api POST "$Admin/admin/order/$order1/ship" @{ company = 'SF'; shipNo = "SF1$tag" } $at
$null = Api POST "$Portal/portal/order/$order1/confirm" @{} $t1
$st = (Api GET "$Portal/portal/order/$order1" $null $t1).data.status
Step 'order finished' ($st -eq 3) "status=$st"

# ---------------------------------------------------------------- review images (local disk)
# 1x1 PNG：脚本自造一张图，不依赖仓库里放二进制
$png = [Convert]::FromBase64String(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==')
$imgPath = Join-Path $outDir 'tiny.png'
[System.IO.File]::WriteAllBytes($imgPath, $png)

curl.exe -s -S -X POST "$Portal/portal/upload/image" -H "Authorization: Bearer $t1" `
    -F "file=@$imgPath" -o $respFile
$imgUrl = (Get-Content $respFile -Raw -Encoding UTF8 | ConvertFrom-Json).data
Step 'upload image returns url' ($imgUrl -like '/uploads/*') "url=$imgUrl"

# 图片由应用直接对外提供（静态映射），换个对象存储时这里会变成 CDN 域名
$imgCode = curl.exe -s -o NUL -w '%{http_code}' "http://localhost:8090$imgUrl"
Step 'uploaded image is served' ($imgCode -eq '200') "status=$imgCode"

# 伪装成图片的文本文件必须拒
$badPath = Join-Path $outDir 'fake.txt'
[System.IO.File]::WriteAllText($badPath, 'not an image')
curl.exe -s -S -X POST "$Portal/portal/upload/image" -H "Authorization: Bearer $t1" `
    -F "file=@$badPath" -o $respFile
$badUp = (Get-Content $respFile -Raw -Encoding UTF8 | ConvertFrom-Json)
Step 'non-image upload rejected' ($badUp.code -eq 7301) "code=$($badUp.code)"

$rv = Api POST "$Portal/portal/review" @{
    orderItemId = $item1; rating = 5; content = 'great product'; images = @($imgUrl)
} $t1
Step 'submit review with image' ($rv.code -eq 200) "code=$($rv.code)"

$dup = Api POST "$Portal/portal/review" @{ orderItemId = $item1; rating = 4; content = 'again' } $t1
Step 'duplicate review rejected (7201)' ($dup.code -eq 7201) "code=$($dup.code)"

# ---------------------------------------------------------------- rating refresh
$detail = Api GET "$Portal/portal/product/$prodId" $null $null
Step 'product rating refreshed to 5.0 / 1' `
    (($detail.data.ratingAvg -eq 5) -and ($detail.data.reviewCount -eq 1)) `
    "avg=$($detail.data.ratingAvg) count=$($detail.data.reviewCount)"

# ${prodId} 必须加花括号：PowerShell 变量名允许 '?'，"$prodId?pageNum" 会被当成一个变量名
$list = Api GET "$Portal/portal/review/product/${prodId}?pageNum=1&pageSize=10" $null $null
$row = @($list.data.list)[0]
Step 'review list exposes label and content' `
    (($list.data.total -eq 1) -and ($row.rating -eq 5) -and ($row.userLabel -eq "Nick$tag")) `
    "total=$($list.data.total) label=$($row.userLabel)"
Step 'review list returns the image' (@($row.images) -contains $imgUrl) ("images=" + (@($row.images) -join ','))

$reviewId = $row.id

# ---------------------------------------------------------------- anonymity
$user2 = "rv2$tag"
Api POST "$Portal/portal/auth/register" @{ username = $user2; password = $pwd; nickname = "Nick2$tag" } $null | Out-Null
$t2 = (Api POST "$Portal/portal/auth/login" @{ username = $user2; password = $pwd } $null).data.token
$addr2 = (Api POST "$Portal/portal/address" @{
    receiver = 'RV2'; phone = '13900000001'; province = 'GD'; city = 'SZ'
    district = 'NS'; detail = 'Review Road 2'; isDefault = 1
} $t2).data
$o2 = (Api POST "$Portal/portal/order/direct" @{ skuId = $skuId; quantity = 1; addressId = $addr2; requestId = "rv2-$tag" } $t2).data.orderId
$null = Api POST "$Portal/portal/order/$o2/pay" @{} $t2
$item2 = @((Api GET "$Portal/portal/order/$o2" $null $t2).data.items)[0].id

# 别人的订单项不能评价
$steal = Api POST "$Portal/portal/review" @{ orderItemId = $item2; rating = 1; content = 'not mine' } $t1
Step 'cannot review another user order item' ($steal.code -ne 200) "code=$($steal.code)"

$null = Api POST "$Admin/admin/order/$o2/ship" @{ company = 'SF'; shipNo = "SF2$tag" } $at
$null = Api POST "$Portal/portal/order/$o2/confirm" @{} $t2

# 外链图片不允许：否则评价区能塞广告/追踪像素（用 user2 自己的订单项，先于匿名评价，失败不会占掉唯一键）
$ext = Api POST "$Portal/portal/review" @{
    orderItemId = $item2; rating = 5; content = 'x'; images = @('https://evil.example.com/a.png')
} $t2
Step 'external image url rejected' ($ext.code -eq 7301) "code=$($ext.code)"

$anon = Api POST "$Portal/portal/review" @{ orderItemId = $item2; rating = 4; content = 'anonymous one'; anonymous = 1 } $t2
Step 'submit anonymous review' ($anon.code -eq 200) "code=$($anon.code)"
$list2 = Api GET "$Portal/portal/review/product/${prodId}?pageNum=1&pageSize=10" $null $null
$labels = @($list2.data.list | ForEach-Object { $_.userLabel })
# 断言"列表里看不到匿名用户的昵称"，而不是比对中文"匿名用户"（CJK 字面量会被 PS 5.1 的 ANSI 解码搞坏）
Step 'anonymous review hides nickname' (-not ($labels -contains "Nick2$tag")) ("labels=" + ($labels -join ' | '))

$detail2 = Api GET "$Portal/portal/product/$prodId" $null $null
Step 'rating averaged to 4.5 / 2' `
    (($detail2.data.ratingAvg -eq 4.5) -and ($detail2.data.reviewCount -eq 2)) `
    "avg=$($detail2.data.ratingAvg) count=$($detail2.data.reviewCount)"

# ---------------------------------------------------------------- admin hide / restore
$adminPage = Api GET "$Admin/admin/review/page?pageNum=1&pageSize=10&productId=$prodId" $null $at
Step 'admin review page' ($adminPage.data.total -eq 2) "total=$($adminPage.data.total)"

$null = Api PUT "$Admin/admin/review/$reviewId/status?status=0" $null $at
$detail3 = Api GET "$Portal/portal/product/$prodId" $null $null
Step 'hide review drops it from rating' `
    (($detail3.data.reviewCount -eq 1) -and ($detail3.data.ratingAvg -eq 4.0)) `
    "avg=$($detail3.data.ratingAvg) count=$($detail3.data.reviewCount)"
$visible = (Api GET "$Portal/portal/review/product/${prodId}?pageNum=1&pageSize=10" $null $null).data.total
Step 'hidden review not visible to buyers' ($visible -eq 1) "visible=$visible"

$null = Api PUT "$Admin/admin/review/$reviewId/status?status=1" $null $at
$detail4 = Api GET "$Portal/portal/product/$prodId" $null $null
Step 'restore puts it back' `
    (($detail4.data.reviewCount -eq 2) -and ($detail4.data.ratingAvg -eq 4.5)) `
    "avg=$($detail4.data.ratingAvg) count=$($detail4.data.reviewCount)"

# ---------------------------------------------------------------- cleanup
# 商品删除 + 评价隐藏：不污染演示商品和后台默认列表
$null = Api PUT "$Admin/admin/review/$reviewId/status?status=0" $null $at
$null = Api DELETE "$Admin/admin/product/$prodId" $null $at
Step 'cleanup done' $true 'product removed, review hidden'

Write-Host ''
Write-Host ("RESULT fails={0}" -f $fails)
if ($fails -gt 0) { exit 1 }
