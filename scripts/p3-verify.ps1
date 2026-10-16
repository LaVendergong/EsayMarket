# ============================================================================
# P3 验证：购物车 + 订单
#   - 购物车：加购/合并/改数量/选中/删除/失效与库存校验/越权
#   - 下单：结算选中项、地址（默认/指定/缺失）、金额与快照、购物车清空
#   - 库存：下单锁定（locked_stock）、支付扣减（stock）、取消/关单释放 —— 防超卖
#   - 状态机：0 待支付 ->(支付) 1 已支付 ->(发货) 2 已发货 ->(确认) 3 已完成；0 ->(取消/超时/后台关闭) 4 已关闭
#   - 超时关单：把 expire_time 改成过去，等 admin 定时任务自动关单并释放库存
#   - 真并发防超卖：两个用户同时对「库存=1」的 SKU 下单，只能成功一单
#
# 用法：
#   powershell -NoProfile -ExecutionPolicy Bypass -File D:\projects\mall\scripts\p3-verify.ps1
#   参数：-SkipBuild / -SkipRestart / -SkipCleanup / -TimeoutWaitSeconds 40
# 证据：tmp\p3\*.json + tmp\p3\summary.txt
# ============================================================================
[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [switch]$SkipRestart,
    [switch]$SkipCleanup,
    [switch]$KeepArtifacts,
    [string]$MysqlPassword = '521131',
    [string]$AdminUser = 'malladmin',
    [string]$AdminPass = 'admin123456',
    [string]$User1 = 'p3test1',
    [string]$User2 = 'p3test2',
    [string]$UserPass = 'p3test123456',
    [int]$TimeoutWaitSeconds = 40
)

$ErrorActionPreference = 'Stop'
$root   = Split-Path -Parent $PSScriptRoot
$portal = 'http://localhost:8090'
$admin  = 'http://localhost:8081'
$mysql  = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$java   = 'D:\jdks\jdk-17.0.12\bin\java.exe'
$outDir = Join-Path $root 'tmp\p3'
$reqFile = Join-Path $outDir '_request.json'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$script:results = New-Object System.Collections.Generic.List[object]

. (Join-Path $PSScriptRoot 'e2e-lib.ps1')

# Windows PowerShell 5.1 需要显式加载 System.Net.Http 才能用 HttpClient（并发下单测试用）
try { Add-Type -AssemblyName System.Net.Http -ErrorAction SilentlyContinue } catch { }

# --------------------------------------------------------------------------- 局部助手

function New-TestUser([string]$username, [string]$password, [string]$tag) {
    Http-Json -Method POST -Url "$portal/api/portal/auth/register" -Body @{ username = $username; password = $password; nickname = $username } -Name "$tag-register" | Out-Null
    $r = Http-Json -Method POST -Url "$portal/api/portal/auth/login" -Body @{ username = $username; password = $password } -Name "$tag-login"
    if ((Code $r) -eq 200) { return $r.body.data.token }
    return $null
}

function Add-TestAddress([string]$token, [string]$tag) {
    $r = Http-Json -Method POST -Url "$portal/api/portal/address" -Token $token -Body @{
        receiver = '张三'; phone = '13800000000'; province = '广东省'; city = '深圳市'
        district = '南山区'; detail = '科技园1号'; isDefault = 1
    } -Name "$tag-address"
    return (Payload $r)
}

function Get-Sku([string]$token, [long]$productId, [string]$spec) {
    $r = Http-Json -Method GET -Url "$admin/api/admin/product/$productId" -Token $token -Name "20-sku-detail-$spec"
    foreach ($s in @((Payload $r).skus)) { if ($s.spec -eq $spec) { return $s } }
    return $null
}

# 直接读库拿「总库存,锁定库存」，比接口更精确
function Sku-State([long]$skuId) {
    return (Invoke-Mysql "USE mall; SELECT CONCAT(stock, ',', IFNULL(locked_stock, 0)) FROM t_sku WHERE id = $skuId;").Trim()
}

function Order-Status([long]$orderId) {
    return (Invoke-Mysql "USE mall; SELECT status FROM t_order WHERE id = $orderId;").Trim()
}

# 脚本自己造的数据统一带 p3test / 'P3 Test' 前缀，跑前跑后都真删，保证可重复执行且不留垃圾
function Remove-TestArtifacts {
    Invoke-Mysql ("USE mall; " +
        "DELETE FROM t_order_item WHERE order_id IN (SELECT id FROM t_order WHERE user_id IN (SELECT id FROM t_user WHERE username LIKE 'p3test%')); " +
        "DELETE FROM t_order WHERE user_id IN (SELECT id FROM t_user WHERE username LIKE 'p3test%'); " +
        "DELETE FROM t_cart WHERE user_id IN (SELECT id FROM t_user WHERE username LIKE 'p3test%'); " +
        "DELETE FROM t_address WHERE user_id IN (SELECT id FROM t_user WHERE username LIKE 'p3test%'); " +
        "DELETE FROM t_sku WHERE product_id IN (SELECT id FROM t_product WHERE name LIKE 'P3 Test%'); " +
        "DELETE FROM t_product WHERE name LIKE 'P3 Test%'; " +
        "DELETE FROM t_category WHERE name LIKE 'P3 Test%'; " +
        "DELETE FROM t_user WHERE username LIKE 'p3test%';") | Out-Null
}

# 真并发下单：同一个 PowerShell 进程里同时发出两个 POST（不 await 第一个），让服务端真正并发处理。
# 不用 Start-Process 起两个 curl —— -ArgumentList 只是用空格拼接，带空格的 header 会被拆散。
function Start-RaceOrder([string]$token) {
    $client = New-Object System.Net.Http.HttpClient
    $client.DefaultRequestHeaders.Add('Authorization', "Bearer $token")
    $content = New-Object System.Net.Http.StringContent('{}', [System.Text.Encoding]::UTF8, 'application/json')
    return [pscustomobject]@{ client = $client; task = $client.PostAsync("$portal/api/portal/order", $content) }
}

function Complete-RaceOrder($race, [string]$tag) {
    $resp = $race.task.GetAwaiter().GetResult()
    $body = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    Write-Text (Join-Path $outDir "$tag.json") $body
    $parsed = $null
    if ($body) { try { $parsed = $body | ConvertFrom-Json } catch { } }
    $race.client.Dispose()
    return [pscustomobject]@{ http = [int]$resp.StatusCode; body = $parsed; raw = $body }
}

# --------------------------------------------------------------------------- 1. 重启 / 构建
if (-not $SkipRestart) {
    Write-Host '== stopping apps =='
    Stop-MallApps
}

if (-not $SkipBuild) {
    Write-Host '== build: build.bat clean install =='
    & cmd.exe /c "$root\build.bat clean install > $root\build.log 2>&1"
    $buildLog = Read-Text (Join-Path $root 'build.log')
    Check 'mvn clean install' (($buildLog -match 'BUILD SUCCESS') -and ($buildLog -match 'BUILD_EXIT=0')) '见 build.log'
    if (-not ($buildLog -match 'BUILD SUCCESS')) {
        Write-Host ($buildLog | Select-String -Pattern 'ERROR' | Select-Object -First 20 | Out-String)
        exit 1
    }
}

if (-not $SkipRestart) {
    Write-Host '== starting apps =='
    if (-not (Start-MallApps 'P3')) { exit 1 }
}

# --------------------------------------------------------------------------- 2. 清理上轮残留
if (-not $SkipCleanup) {
    Write-Host '== cleanup previous test artifacts =='
    Remove-TestArtifacts
}

# --------------------------------------------------------------------------- 3. 账号与测试商品
Write-Host '== bootstrap accounts =='
$adminLogin = Http-Json -Method POST -Url "$admin/api/admin/auth/login" -Body @{ username = $AdminUser; password = $AdminPass } -Name '01-admin-login'
if ((Code $adminLogin) -ne 200) {
    Invoke-Mysql "USE mall; DELETE FROM t_user WHERE username='$AdminUser';" | Out-Null
    Http-Json -Method POST -Url "$portal/api/portal/auth/register" -Body @{ username = $AdminUser; password = $AdminPass; nickname = 'admin' } -Name '02-admin-register' | Out-Null
    Invoke-Mysql "USE mall; UPDATE t_user SET role=1, status=1, deleted=0 WHERE username='$AdminUser';" | Out-Null
    $adminLogin = Http-Json -Method POST -Url "$admin/api/admin/auth/login" -Body @{ username = $AdminUser; password = $AdminPass } -Name '01-admin-login'
}
$adminToken = $null
if ((Code $adminLogin) -eq 200) { $adminToken = $adminLogin.body.data.token }

$token1 = New-TestUser $User1 $UserPass '03-user1'
$token2 = New-TestUser $User2 $UserPass '04-user2'
Check '测试用户注册+登录（2 个）' ($null -ne $token1 -and $null -ne $token2) "user1=$([bool]$token1) user2=$([bool]$token2)"
Check '管理员可登录后台' ((Code $adminLogin) -eq 200 -and $null -ne $adminToken) "http=$($adminLogin.http) code=$(Code $adminLogin)"
if (-not $token1 -or -not $token2 -or -not $adminToken) { Write-Host '账号初始化失败，终止'; exit 1 }

$catR = Http-Json -Method POST -Url "$admin/api/admin/category" -Token $adminToken -Body @{ parentId = 0; name = 'P3 Test Cat'; sort = 97 } -Name '05-category'
$catId = Payload $catR
$prodR = Http-Json -Method POST -Url "$admin/api/admin/product" -Token $adminToken -Body @{
    categoryId = $catId; name = 'P3 Test Widget'; subTitle = 'p3'; price = 100.00
    skus = @(
        @{ skuCode = 'P3-A'; spec = 'A'; price = 100.00; stock = 5 },
        @{ skuCode = 'P3-B'; spec = 'B'; price = 50.00;  stock = 4 },
        @{ skuCode = 'P3-C'; spec = 'C'; price = 30.00;  stock = 3 },
        @{ skuCode = 'P3-D'; spec = 'D'; price = 20.00;  stock = 1 }
    )
} -Name '06-product'
$prodId = Payload $prodR
$skuA = Get-Sku $adminToken $prodId 'A'
$skuB = Get-Sku $adminToken $prodId 'B'
$skuC = Get-Sku $adminToken $prodId 'C'
$skuD = Get-Sku $adminToken $prodId 'D'
Check '测试商品与 4 个 SKU 创建成功（A5/B4/C3/D1）' (
    (Code $prodR) -eq 200 -and $skuA -and $skuB -and $skuC -and $skuD -and
    $skuA.availableStock -eq 5 -and $skuB.availableStock -eq 4 -and $skuD.availableStock -eq 1
) "productId=$prodId A=$($skuA.id)/$($skuA.availableStock) B=$($skuB.id)/$($skuB.availableStock) C=$($skuC.id) D=$($skuD.id)/$($skuD.availableStock)"
if (-not $prodId) { Write-Host '测试商品创建失败，终止'; exit 1 }

# --------------------------------------------------------------------------- 4. 购物车
Write-Host '== cart =='
$c1 = Http-Json -Method GET -Url "$portal/api/portal/cart" -Token $token1 -Name '10-cart-empty'
Check '购物车初始为空' ((Code $c1) -eq 200 -and @(Payload $c1).Count -eq 0) "code=$(Code $c1) size=$(@(Payload $c1).Count)"

$o0 = Http-Json -Method POST -Url "$portal/api/portal/order" -Token $token1 -Body @{} -Name '11-order-empty-cart'
Check '空购物车下单 -> 2004' ((Code $o0) -eq 2004) "code=$(Code $o0)"

$addA1 = Http-Json -Method POST -Url "$portal/api/portal/cart" -Token $token1 -Body @{ skuId = $skuA.id; quantity = 1 } -Name '12-cart-add-A1'
$itemA = Payload $addA1
$addA2 = Http-Json -Method POST -Url "$portal/api/portal/cart" -Token $token1 -Body @{ skuId = $skuA.id; quantity = 2 } -Name '13-cart-add-A2'
$c2 = Http-Json -Method GET -Url "$portal/api/portal/cart" -Token $token1 -Name '14-cart-list'
$lineA = @(Payload $c2) | Where-Object { $_.skuId -eq $skuA.id }
Check '同一 SKU 加购累加而不是新增行（1+2 -> 3）' (
    (Code $addA2) -eq 200 -and @(Payload $c2).Count -eq 1 -and $lineA.quantity -eq 3
) "size=$(@(Payload $c2).Count) qty=$($lineA.quantity)"

$updQty = Http-Json -Method PUT -Url "$portal/api/portal/cart/$itemA`?quantity=2" -Token $token1 -Name '15-cart-qty-2'
$c3 = Http-Json -Method GET -Url "$portal/api/portal/cart" -Token $token1 -Name '16-cart-list-2'
$lineA = @(Payload $c3) | Where-Object { $_.skuId -eq $skuA.id }
Check '修改数量生效（2），价格与可售库存正确透出' (
    (Code $updQty) -eq 200 -and $lineA.quantity -eq 2 -and [decimal]$lineA.price -eq 100.00 -and
    $lineA.availableStock -eq 5 -and $lineA.valid -eq $true -and [decimal]$lineA.totalPrice -eq 200.00
) "qty=$($lineA.quantity) price=$($lineA.price) available=$($lineA.availableStock) valid=$($lineA.valid)"

$addB = Http-Json -Method POST -Url "$portal/api/portal/cart" -Token $token1 -Body @{ skuId = $skuB.id; quantity = 1 } -Name '17-cart-add-B'
$itemB = Payload $addB
$addC = Http-Json -Method POST -Url "$portal/api/portal/cart" -Token $token1 -Body @{ skuId = $skuC.id; quantity = 1 } -Name '18-cart-add-C'
$itemC = Payload $addC
$uncheck = Http-Json -Method PUT -Url "$portal/api/portal/cart/$itemC/checked?checked=0" -Token $token1 -Name '19-cart-uncheck-C'
$c4 = Http-Json -Method GET -Url "$portal/api/portal/cart" -Token $token1 -Name '20-cart-list-3'
$lineC = @(Payload $c4) | Where-Object { $_.skuId -eq $skuC.id }
Check '加购 3 个 SKU；C 取消选中成功' (
    @(Payload $c4).Count -eq 3 -and (Code $uncheck) -eq 200 -and $lineC.checked -eq 0
) "size=$(@(Payload $c4).Count) C.checked=$($lineC.checked)"

$badSku = Http-Json -Method POST -Url "$portal/api/portal/cart" -Token $token1 -Body @{ skuId = 99999999; quantity = 1 } -Name '21-cart-bad-sku'
Check '加购不存在的 SKU -> 2007' ((Code $badSku) -eq 2007) "code=$(Code $badSku)"
$badQty = Http-Json -Method POST -Url "$portal/api/portal/cart" -Token $token1 -Body @{ skuId = $skuA.id; quantity = 0 } -Name '22-cart-qty-zero'
Check '加购数量 0 -> 400' ((Code $badQty) -eq 400) "code=$(Code $badQty)"
$overStock = Http-Json -Method POST -Url "$portal/api/portal/cart" -Token $token1 -Body @{ skuId = $skuB.id; quantity = 99 } -Name '23-cart-over-stock'
Check '加购超过可售库存 -> 2003' ((Code $overStock) -eq 2003) "code=$(Code $overStock)"
$crossUser = Http-Json -Method PUT -Url "$portal/api/portal/cart/$itemA`?quantity=1" -Token $token2 -Name '24-cart-cross-user'
Check '操作别人的购物车条目 -> 2009' ((Code $crossUser) -eq 2009) "code=$(Code $crossUser)"
$anonCart = Http-Json -Method GET -Url "$portal/api/portal/cart" -Name '25-cart-anon'
Check '匿名访问购物车 -> HTTP 401' ($anonCart.http -eq 401) "http=$($anonCart.http)"
$missParam = Http-Json -Method PUT -Url "$portal/api/portal/cart/$itemA" -Token $token1 -Name '26-cart-missing-param'
Check '缺少必填参数 -> code 400（不是 500）' ((Code $missParam) -eq 400) "code=$(Code $missParam) msg=$($missParam.body.message)"

# --------------------------------------------------------------------------- 5. 下单：地址
Write-Host '== order: address =='
$noAddr = Http-Json -Method POST -Url "$portal/api/portal/order" -Token $token1 -Body @{} -Name '30-order-no-address'
Check '有商品但没收货地址 -> 2010' ((Code $noAddr) -eq 2010) "code=$(Code $noAddr) msg=$($noAddr.body.message)"
$addr1 = Add-TestAddress $token1 '31-user1'
Check '新增收货地址（user1）' ($null -ne $addr1 -and $addr1 -gt 0) "addressId=$addr1"
$addr2 = Add-TestAddress $token2 '32-user2'
Check '新增收货地址（user2）' ($null -ne $addr2 -and $addr2 -gt 0) "addressId=$addr2"

# --------------------------------------------------------------------------- 6. 下单主流程
Write-Host '== order: create =='
$ord1 = Http-Json -Method POST -Url "$portal/api/portal/order" -Token $token1 -Body @{} -Name '40-order-create'
$d1 = Payload $ord1
$order1 = $d1.orderId
Check '下单成功（选中项 A×2 + B×1 = 250.00，状态待支付）' (
    (Code $ord1) -eq 200 -and $order1 -gt 0 -and [decimal]$d1.payAmount -eq 250.00 -and $d1.status -eq 0 -and
    [bool]$d1.orderNo -and [bool]$d1.expireTime
) "orderId=$order1 payAmount=$($d1.payAmount) status=$($d1.status) orderNo=$($d1.orderNo)"

$c5 = Http-Json -Method GET -Url "$portal/api/portal/cart" -Token $token1 -Name '41-cart-after-order'
$leftOver = @(Payload $c5)
Check '下单只清理选中项，未选中的 C 保留' (
    $leftOver.Count -eq 1 -and $leftOver[0].skuId -eq $skuC.id -and $leftOver[0].checked -eq 0
) "size=$($leftOver.Count)"

$od1 = Http-Json -Method GET -Url "$portal/api/portal/order/$order1" -Token $token1 -Name '42-order-detail'
$det1 = Payload $od1
Check '订单详情：2 条明细 + 收货信息快照（中文正确）' (
    (Code $od1) -eq 200 -and @($det1.items).Count -eq 2 -and $det1.receiver -eq '张三' -and
    $det1.receiverAddr -match '科技园' -and $det1.statusText -eq '待支付'
) "items=$(@($det1.items).Count) receiver=$($det1.receiver) addr=$($det1.receiverAddr) statusText=$($det1.statusText)"

Check '下单锁定库存（A 5/2，B 4/1）' ((Sku-State $skuA.id) -eq '5,2' -and (Sku-State $skuB.id) -eq '4,1') "A=$(Sku-State $skuA.id) B=$(Sku-State $skuB.id)"

$pl = Http-Json -Method GET -Url "$portal/api/portal/product/page?categoryId=$catId&pageSize=50" -Name '43-product-list-stock'
$pRow = @((Items $pl) | Where-Object { $_.id -eq $prodId })
Check '商品列表库存=可售合计（A3+B3+C3+D1=10，已扣掉锁定）' (
    $pRow.Count -eq 1 -and $pRow[0].stock -eq 10
) "stock=$($pRow[0].stock)"

$myOrders = Http-Json -Method GET -Url "$portal/api/portal/order/page?pageSize=50" -Token $token1 -Name '44-my-orders'
$myIds = @((Items $myOrders) | ForEach-Object { $_.id })
$otherOrders = Http-Json -Method GET -Url "$portal/api/portal/order/page?pageSize=50" -Token $token2 -Name '45-user2-orders'
$otherIds = @((Items $otherOrders) | ForEach-Object { $_.id })
Check '订单列表按用户隔离（user1 有、user2 没有）' (
    ($myIds -contains $order1) -and ($otherIds -notcontains $order1)
) "user1=$($myIds -join ',') user2=$($otherIds -join ',')"

$crossDetail = Http-Json -Method GET -Url "$portal/api/portal/order/$order1" -Token $token2 -Name '46-order-cross-user'
Check '越权查别人订单 -> 3001' ((Code $crossDetail) -eq 3001) "code=$(Code $crossDetail)"
$crossPay = Http-Json -Method POST -Url "$portal/api/portal/order/$order1/pay" -Token $token2 -Name '47-order-cross-pay'
Check '越权支付别人订单 -> 3001' ((Code $crossPay) -eq 3001) "code=$(Code $crossPay)"
$anonOrder = Http-Json -Method GET -Url "$portal/api/portal/order/page" -Name '48-order-anon'
Check '匿名访问订单 -> HTTP 401' ($anonOrder.http -eq 401) "http=$($anonOrder.http)"

# --------------------------------------------------------------------------- 7. 状态机
Write-Host '== order: state machine =='
$confirmEarly = Http-Json -Method POST -Url "$portal/api/portal/order/$order1/confirm" -Token $token1 -Name '50-confirm-unshipped'
Check '未发货就确认收货 -> 3002' ((Code $confirmEarly) -eq 3002) "code=$(Code $confirmEarly)"

$pay1 = Http-Json -Method POST -Url "$portal/api/portal/order/$order1/pay" -Token $token1 -Name '51-order-pay'
$od2 = Http-Json -Method GET -Url "$portal/api/portal/order/$order1" -Token $token1 -Name '52-order-detail-paid'
Check '支付成功：状态=已支付，payTime 有值' (
    (Code $pay1) -eq 200 -and (Payload $od2).status -eq 1 -and (Payload $od2).statusText -eq '已支付' -and [bool](Payload $od2).payTime
) "code=$(Code $pay1) status=$((Payload $od2).status)"
Check '支付后真正扣减总库存并释放锁定（A 3/0，B 3/0）' ((Sku-State $skuA.id) -eq '3,0' -and (Sku-State $skuB.id) -eq '3,0') "A=$(Sku-State $skuA.id) B=$(Sku-State $skuB.id)"

$payAgain = Http-Json -Method POST -Url "$portal/api/portal/order/$order1/pay" -Token $token1 -Name '53-order-pay-again'
Check '重复支付 -> 3002' ((Code $payAgain) -eq 3002) "code=$(Code $payAgain)"
$cancelPaid = Http-Json -Method POST -Url "$portal/api/portal/order/$order1/cancel" -Token $token1 -Name '54-order-cancel-paid'
Check '已支付订单不能取消 -> 3002' ((Code $cancelPaid) -eq 3002) "code=$(Code $cancelPaid)"

$shipUnpaid = Http-Json -Method POST -Url "$admin/api/admin/order/$order1/ship" -Token $adminToken -Name '55-order-ship'
$od3 = Http-Json -Method GET -Url "$portal/api/portal/order/$order1" -Token $token1 -Name '56-order-detail-shipped'
Check '后台发货：已支付 -> 已发货' ((Code $shipUnpaid) -eq 200 -and (Payload $od3).status -eq 2) "code=$(Code $shipUnpaid) status=$((Payload $od3).status)"
$shipAgain = Http-Json -Method POST -Url "$admin/api/admin/order/$order1/ship" -Token $adminToken -Name '57-order-ship-again'
Check '重复发货 -> 3002' ((Code $shipAgain) -eq 3002) "code=$(Code $shipAgain)"
$confirm = Http-Json -Method POST -Url "$portal/api/portal/order/$order1/confirm" -Token $token1 -Name '58-order-confirm'
$od4 = Http-Json -Method GET -Url "$portal/api/portal/order/$order1" -Token $token1 -Name '59-order-detail-finished'
Check '确认收货：已发货 -> 已完成' ((Code $confirm) -eq 200 -and (Payload $od4).status -eq 3) "code=$(Code $confirm) status=$((Payload $od4).status)"

# --------------------------------------------------------------------------- 8. 取消 / 后台关闭释放库存
Write-Host '== order: cancel / close =='
$updC = Http-Json -Method PUT -Url "$portal/api/portal/cart/$itemC/checked?checked=1" -Token $token1 -Name '60-cart-check-C'
$updC2 = Http-Json -Method PUT -Url "$portal/api/portal/cart/$itemC`?quantity=2" -Token $token1 -Name '61-cart-C-qty2'
Check '把 C 重新选中并改数量为 2' ((Code $updC) -eq 200 -and (Code $updC2) -eq 200) "check=$(Code $updC) qty=$(Code $updC2)"
$ord2r = Http-Json -Method POST -Url "$portal/api/portal/order" -Token $token1 -Body @{} -Name '62-order-C'
$order2 = (Payload $ord2r).orderId
Check 'C×2 下单成功（60.00，锁定 C 3/2）' (
    (Code $ord2r) -eq 200 -and $order2 -gt 0 -and [decimal](Payload $ord2r).payAmount -eq 60.00 -and (Sku-State $skuC.id) -eq '3,2'
) "orderId=$order2 code=$(Code $ord2r) C=$(Sku-State $skuC.id)"
$cancel2 = Http-Json -Method POST -Url "$portal/api/portal/order/$order2/cancel" -Token $token1 -Name '63-order-C-cancel'
Check '取消待支付订单：状态=已关闭且释放锁定库存（C 3/0）' (
    (Code $cancel2) -eq 200 -and (Order-Status $order2) -eq '4' -and (Sku-State $skuC.id) -eq '3,0'
) "code=$(Code $cancel2) status=$(Order-Status $order2) C=$(Sku-State $skuC.id)"
$cancel2b = Http-Json -Method POST -Url "$portal/api/portal/order/$order2/cancel" -Token $token1 -Name '64-order-C-cancel-again'
Check '重复取消 -> 3002' ((Code $cancel2b) -eq 3002) "code=$(Code $cancel2b)"

$addC2 = Http-Json -Method POST -Url "$portal/api/portal/cart" -Token $token1 -Body @{ skuId = $skuC.id; quantity = 1 } -Name '65-cart-C-again'
$ord6r = Http-Json -Method POST -Url "$portal/api/portal/order" -Token $token1 -Body @{} -Name '66-order-C2'
$order6 = (Payload $ord6r).orderId
Check '再下一单待支付订单（用于后台关闭）' ((Code $ord6r) -eq 200 -and $order6 -gt 0) "orderId=$order6 code=$(Code $ord6r)"
$close6 = Http-Json -Method POST -Url "$admin/api/admin/order/$order6/close" -Token $adminToken -Name '67-admin-close'
Check '后台关闭待支付订单：状态=已关闭且释放库存' (
    (Code $close6) -eq 200 -and (Order-Status $order6) -eq '4' -and (Sku-State $skuC.id) -eq '3,0'
) "code=$(Code $close6) status=$(Order-Status $order6) C=$(Sku-State $skuC.id)"

# --------------------------------------------------------------------------- 9. 防超卖
Write-Host '== oversell guard =='
$addA2u = Http-Json -Method POST -Url "$portal/api/portal/cart" -Token $token2 -Body @{ skuId = $skuA.id; quantity = 1 } -Name '70-user2-cart-A'
$addA3u1 = Http-Json -Method POST -Url "$portal/api/portal/cart" -Token $token1 -Body @{ skuId = $skuA.id; quantity = 3 } -Name '71-user1-cart-A3'
$ord3r = Http-Json -Method POST -Url "$portal/api/portal/order" -Token $token1 -Body @{} -Name '72-order-A3'
$order3 = (Payload $ord3r).orderId
Check 'user1 吃掉 A 剩余 3 件（A 3/3）' ((Code $ord3r) -eq 200 -and (Sku-State $skuA.id) -eq '3,3') "code=$(Code $ord3r) A=$(Sku-State $skuA.id)"
$ord3u2 = Http-Json -Method POST -Url "$portal/api/portal/order" -Token $token2 -Body @{} -Name '73-user2-order-A'
Check '库存已被锁完，user2 下单 -> 2003（下单时兜底，不是只在加购时拦）' (
    (Code $ord3u2) -eq 2003 -and (Sku-State $skuA.id) -eq '3,3'
) "code=$(Code $ord3u2) A=$(Sku-State $skuA.id)"
$cancel3 = Http-Json -Method POST -Url "$portal/api/portal/order/$order3/cancel" -Token $token1 -Name '74-order-A3-cancel'
Check '取消后库存释放回 A 3/0' ((Code $cancel3) -eq 200 -and (Sku-State $skuA.id) -eq '3,0') "A=$(Sku-State $skuA.id)"
$clearU2 = Http-Json -Method DELETE -Url "$portal/api/portal/cart/checked" -Token $token2 -Name '75-user2-cart-clear'
Check '清空 user2 选中项' ((Code $clearU2) -eq 200) "code=$(Code $clearU2)"

# --------------------------------------------------------------------------- 10. 真并发下单（库存=1 的 D）
Write-Host '== concurrent oversell (stock=1) =='
Http-Json -Method POST -Url "$portal/api/portal/cart" -Token $token1 -Body @{ skuId = $skuD.id; quantity = 1 } -Name '81-user1-cart-D' | Out-Null
Http-Json -Method POST -Url "$portal/api/portal/cart" -Token $token2 -Body @{ skuId = $skuD.id; quantity = 1 } -Name '82-user2-cart-D' | Out-Null
$p1 = Start-RaceOrder $token1
$p2 = Start-RaceOrder $token2
$rr1 = Complete-RaceOrder $p1 'race-u1'
$rr2 = Complete-RaceOrder $p2 'race-u2'
$r1 = $rr1.body
$r2 = $rr2.body
$raceCodes = @($r1.code, $r2.code)
$winnerCount = @($raceCodes | Where-Object { $_ -eq 200 }).Count
$loserCount = @($raceCodes | Where-Object { $_ -eq 2003 }).Count
Check '并发下单同一 SKU：恰好一单成功、一单 2003（无超卖）' (
    $winnerCount -eq 1 -and $loserCount -eq 1 -and (Sku-State $skuD.id) -eq '1,1'
) "codes=$($raceCodes -join ',') D=$(Sku-State $skuD.id)"
$winOrder = $null; $winToken = $null
if ($r1.code -eq 200) { $winOrder = $r1.data.orderId; $winToken = $token1 } else { $winOrder = $r2.data.orderId; $winToken = $token2 }
Check '并发下单的赢家订单已落库' ($null -ne $winOrder -and $winOrder -gt 0) "winnerOrderId=$winOrder"
if ($winOrder) {
    $cancelWin = Http-Json -Method POST -Url "$portal/api/portal/order/$winOrder/cancel" -Token $winToken -Name '83-race-winner-cancel'
    Check '取消并发赢家订单后 D 释放回 1/0' ((Code $cancelWin) -eq 200 -and (Sku-State $skuD.id) -eq '1,0') "D=$(Sku-State $skuD.id)"
}

# --------------------------------------------------------------------------- 11. 超时关单
Write-Host '== timeout close =='
Http-Json -Method POST -Url "$portal/api/portal/cart" -Token $token1 -Body @{ skuId = $skuB.id; quantity = 1 } -Name '90-user1-cart-B' | Out-Null
$ord5r = Http-Json -Method POST -Url "$portal/api/portal/order" -Token $token1 -Body @{} -Name '91-order-B'
$order5 = (Payload $ord5r).orderId
Check '下单 B 并锁定（B 3/1）' ((Code $ord5r) -eq 200 -and (Sku-State $skuB.id) -eq '3,1') "code=$(Code $ord5r) B=$(Sku-State $skuB.id)"
$expireSql = "USE mall; UPDATE t_order SET expire_time = DATE_SUB(NOW(), INTERVAL 1 MINUTE) WHERE id = $order5;"
Invoke-Mysql $expireSql | Out-Null
$deadline = (Get-Date).AddSeconds($TimeoutWaitSeconds)
$closed = $false
while ((Get-Date) -lt $deadline) {
    if ((Order-Status $order5) -eq '4') { $closed = $true; break }
    Start-Sleep -Seconds 2
}
Check "超时关单：admin 定时任务自动关闭（等待 $TimeoutWaitSeconds 秒内）" ($closed) "status=$(Order-Status $order5)"
Check '超时关单同时释放锁定库存（B 3/0）' ((Sku-State $skuB.id) -eq '3,0') "B=$(Sku-State $skuB.id)"

# --------------------------------------------------------------------------- 12. 后台订单接口
Write-Host '== admin order apis =='
$ap = Http-Json -Method GET -Url "$admin/api/admin/order/page?pageSize=50" -Token $adminToken -Name '95-admin-order-page'
$apIds = @((Items $ap) | ForEach-Object { $_.id })
Check '后台订单分页能看到测试订单' ((Code $ap) -eq 200 -and ($apIds -contains $order1) -and ($apIds -contains $order5)) "total=$((Payload $ap).total)"
$apClosed = Http-Json -Method GET -Url "$admin/api/admin/order/page?status=4&pageSize=50" -Token $adminToken -Name '96-admin-order-page-closed'
$badStatus = @((Items $apClosed) | Where-Object { $_.status -ne 4 })
Check '后台按状态筛选（status=4 的结果里没有非 4 的）' ((Code $apClosed) -eq 200 -and $badStatus.Count -eq 0 -and (Payload $apClosed).total -ge 1) "total=$((Payload $apClosed).total)"
$apKw = Http-Json -Method GET -Url "$admin/api/admin/order/page?keyword=$($d1.orderNo)&pageSize=50" -Token $adminToken -Name '97-admin-order-keyword'
Check '后台按订单号搜索' ((Code $apKw) -eq 200 -and (Payload $apKw).total -eq 1 -and @((Items $apKw))[0].id -eq $order1) "total=$((Payload $apKw).total)"
$apDetail = Http-Json -Method GET -Url "$admin/api/admin/order/$order1" -Token $adminToken -Name '98-admin-order-detail'
Check '后台订单详情含明细' ((Code $apDetail) -eq 200 -and @((Payload $apDetail).items).Count -eq 2) "items=$(@((Payload $apDetail).items).Count)"
$userAdmin = Http-Json -Method GET -Url "$admin/api/admin/order/page" -Token $token1 -Name '99-admin-page-as-user'
Check '普通用户打后台接口 -> HTTP 403' ($userAdmin.http -eq 403) "http=$($userAdmin.http)"
$anonAdmin = Http-Json -Method GET -Url "$admin/api/admin/order/page" -Name '100-admin-page-anon'
Check '匿名打后台接口 -> HTTP 401' ($anonAdmin.http -eq 401) "http=$($anonAdmin.http)"

# --------------------------------------------------------------------------- 13. 收尾
$emptyAgain = Http-Json -Method POST -Url "$portal/api/portal/order" -Token $token1 -Body @{} -Name '110-order-empty-again'
Check '购物车已清空后下单 -> 2004' ((Code $emptyAgain) -eq 2004) "code=$(Code $emptyAgain)"

# --------------------------------------------------------------------------- 14. 收尾：清掉本次测试数据（-KeepArtifacts 可保留）
if (-not $KeepArtifacts) {
    Write-Host '== cleanup test artifacts =='
    Remove-TestArtifacts
    $left = (Invoke-Mysql ("USE mall; SELECT CONCAT(" +
        "(SELECT COUNT(*) FROM t_product WHERE name LIKE 'P3 Test%'), ',', " +
        "(SELECT COUNT(*) FROM t_order o JOIN t_user u ON o.user_id = u.id WHERE u.username LIKE 'p3test%'), ',', " +
        "(SELECT COUNT(*) FROM t_user WHERE username LIKE 'p3test%'), ',', " +
        "(SELECT COUNT(*) FROM t_sku WHERE locked_stock > 0));")).Trim()
    Check '测试数据已清理且无残留锁定库存（商品/订单/用户/锁定SKU = 0,0,0,0）' ($left -eq '0,0,0,0') "left=$left"
}

# --------------------------------------------------------------------------- 汇总
$fail = Save-Summary 'P3 验证汇总'
if ($fail -gt 0) { exit 1 }
exit 0
