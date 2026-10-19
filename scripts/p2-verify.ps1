# ============================================================================
# P2 商品域 E2E 验证脚本（分类树 / SPU / SKU / 列表 / 详情 / 搜索 / 后台商品管理）
#
# 用法（Windows PowerShell 或 pwsh 均可）：
#   powershell -NoProfile -ExecutionPolicy Bypass -File D:\projects\mall\scripts\p2-verify.ps1
#   pwsh -File .\scripts\p2-verify.ps1 -SkipBuild          # 不重新构建，只重启+验证
#   pwsh -File .\scripts\p2-verify.ps1 -SkipRestart        # 只对当前已启动的服务跑断言
#
# 依赖：curl.exe（Win10+ 自带）、mysql.exe、已构建好的 jar。
# 证据：所有请求/响应落在 tmp\p2\*.json，汇总在 tmp\p2\summary.txt
# ============================================================================
[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [switch]$SkipRestart,
    [switch]$SkipSeed,
    [switch]$SkipCleanup,
    [string]$MysqlPassword = '521131',
    [string]$AdminUser = 'malladmin',
    [string]$AdminPass = 'admin123456'
)

$ErrorActionPreference = 'Stop'
$root   = Split-Path -Parent $PSScriptRoot
$portal = 'http://localhost:8090'
$admin  = 'http://localhost:8081'
$mysql  = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$java   = 'D:\jdks\jdk-17.0.12\bin\java.exe'
$outDir = Join-Path $root 'tmp\p2'
$reqFile = Join-Path $outDir '_request.json'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$script:results = New-Object System.Collections.Generic.List[object]

function Read-Text([string]$path) {
    if (-not (Test-Path $path)) { return '' }
    try {
        $fs = [System.IO.File]::Open($path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
        try {
            $ms = New-Object System.IO.MemoryStream
            $fs.CopyTo($ms)
            return [System.Text.Encoding]::UTF8.GetString($ms.ToArray())
        } finally { $fs.Dispose() }
    } catch { return '' }
}

function Write-Text([string]$path, [string]$text) {
    [System.IO.File]::WriteAllText($path, $text, (New-Object System.Text.UTF8Encoding($false)))
}

function Http-Json {
    param(
        [Parameter(Mandatory)][string]$Method,
        [Parameter(Mandatory)][string]$Url,
        $Body,
        [string]$Token,
        [Parameter(Mandatory)][string]$Name
    )
    $cargs = @('-s', '-S', '-X', $Method, $Url, '-H', 'Content-Type: application/json; charset=utf-8', '-w', '%{http_code}')
    if ($Token) { $cargs += @('-H', "Authorization: Bearer $Token") }
    if ($null -ne $Body) {
        Write-Text $reqFile ($Body | ConvertTo-Json -Depth 12 -Compress)
        # 注意：必须先用变量拼好再入数组。写成 @('--data-binary', '@' + $reqFile) 会被
        # PowerShell 拆成 3 个参数（'--data-binary' / '@' / 路径），curl 直接失败。
        $bodyArg = '@' + $reqFile
        $cargs += @('--data-binary', $bodyArg)
    }
    $respFile = Join-Path $outDir ($Name + '.json')
    Remove-Item $respFile -Force -ErrorAction SilentlyContinue
    $cargs += @('-o', $respFile)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $code = (& curl.exe @cargs 2>$null | Out-String).Trim() } finally { $ErrorActionPreference = $prev }
    $text = Read-Text $respFile
    $parsed = $null
    if ($text) { try { $parsed = $text | ConvertFrom-Json } catch { } }
    return [pscustomobject]@{ http = [int]$code; body = $parsed; raw = $text; file = $respFile }
}

function Check([string]$name, $ok, [string]$detail) {
    $ok = [bool]$ok
    $script:results.Add([pscustomobject]@{ name = $name; ok = $ok; detail = $detail })
    $tag = 'FAIL'; if ($ok) { $tag = 'PASS' }
    Write-Host ("[{0}] {1} :: {2}" -f $tag, $name, $detail)
}

function Code($resp) { if ($resp.body) { return $resp.body.code } else { return $null } }
function Payload($resp) { if ($resp.body) { return $resp.body.data } else { return $null } }
function Items($resp) {
    if (-not $resp.body -or -not $resp.body.data) { return @() }
    return @($resp.body.data.list)
}

function Wait-Log([string]$logPath, [string]$marker, [int]$timeoutSec) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        if ((Read-Text $logPath) -match $marker) { return $true }
        Start-Sleep -Milliseconds 1000
    }
    return $false
}

function Test-Port([int]$port) {
    try {
        $c = New-Object System.Net.Sockets.TcpClient
        $c.Connect('127.0.0.1', $port)
        $c.Close()
        return $true
    } catch { return $false }
}

function Invoke-Mysql([string]$sql) {
    # mysql.exe 会往 stderr 打「Using a password ...」警告；在 $ErrorActionPreference='Stop' 下
    # 这会被当成 NativeCommandError 中断脚本，故局部放行并丢弃 stderr。
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $mysql -uroot "-p$MysqlPassword" -N --default-character-set=utf8mb4 -e $sql 2>$null
        return ($out | Out-String)
    } finally { $ErrorActionPreference = $prev }
}

# 直接用 Start-Process 起 java 并把 stdout/stderr 落到文件：
# 这样进程不会继承本脚本的 stdout 管道（用 run.bat 的 start /b 会让父 shell 等不到 EOF 而被超时杀掉），
# 同时顺手写 logs\<name>.pid 供 stop-apps.ps1 精确停进程。
function Start-MallApp([string]$name, [string]$jar, [string]$log) {
    $errLog = $log -replace '\.log$', '.err.log'
    $p = Start-Process -FilePath $java -ArgumentList @('-jar', $jar) `
        -RedirectStandardOutput $log -RedirectStandardError $errLog -PassThru -WindowStyle Hidden
    Write-Text (Join-Path $root "logs\$name.pid") ([string]$p.Id)
    Write-Host ("   started $name pid=" + $p.Id)
    return $p.Id
}

# --------------------------------------------------------------------------- 1. 停止 / 构建 / 启动
if (-not $SkipRestart) {
    Write-Host '== stopping apps =='
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'scripts\stop-apps.ps1') 2>&1 |
            ForEach-Object { Write-Host "   $_" }
    } finally { $ErrorActionPreference = $prev }
    Remove-Item (Join-Path $root 'logs\*.log') -Force -ErrorAction SilentlyContinue
}

if (-not $SkipBuild) {
    Write-Host '== build: build.bat clean install =='
    & cmd.exe /c "$root\build.bat clean install > $root\build.log 2>&1"
    $buildLog = Read-Text (Join-Path $root 'build.log')
    Check 'mvn clean install' (($buildLog -match 'BUILD SUCCESS') -and ($buildLog -match 'BUILD_EXIT=0')) '见 build.log'
    if (-not ($buildLog -match 'BUILD SUCCESS')) {
        Write-Host ($buildLog | Select-String -Pattern 'ERROR' | Select-Object -First 20 | Out-String)
        Write-Text (Join-Path $outDir 'summary.txt') "BUILD FAILED`n$buildLog"
        exit 1
    }
}

if (-not $SkipRestart) {
    Write-Host '== starting apps =='
    Start-MallApp 'admin'  (Join-Path $root 'backend\mall-admin\target\mall-admin-1.0.0.jar')   (Join-Path $root 'logs\admin.log')  | Out-Null
    Start-MallApp 'portal' (Join-Path $root 'backend\mall-portal\target\mall-portal-1.0.0.jar') (Join-Path $root 'logs\portal.log') | Out-Null
    $okAdmin  = Wait-Log (Join-Path $root 'logs\admin.log')  'Started MallAdminApplication'  120
    $okPortal = Wait-Log (Join-Path $root 'logs\portal.log') 'Started MallPortalApplication' 120
    Check 'mall-admin 启动'  $okAdmin  'logs\admin.log'
    Check 'mall-portal 启动' $okPortal 'logs\portal.log'
    if (-not ($okAdmin -and $okPortal)) {
        $tail = Read-Text (Join-Path $root 'logs\admin.log')
        Write-Host $tail.Substring(0, [Math]::Min(1500, $tail.Length))
        exit 1
    }
}

# --------------------------------------------------------------------------- 2. 清理上轮测试残留 + 演示数据
# 脚本自身产生的数据统一用 'P2 Test' 前缀，跑之前先真删掉，保证断言可重复执行。
if (-not $SkipCleanup) {
    Write-Host '== cleanup previous test artifacts =='
    Invoke-Mysql ("USE mall; " +
        "DELETE FROM t_sku WHERE product_id IN (SELECT id FROM t_product WHERE name LIKE 'P2 Test%'); " +
        "DELETE FROM t_product WHERE name LIKE 'P2 Test%'; " +
        "DELETE FROM t_category WHERE name LIKE 'P2 Test%';") | Out-Null
}

if (-not $SkipSeed) {
    $seed = Join-Path $root 'sql\V3__seed_demo.sql'
    if (Test-Path $seed) {
        Write-Host '== seed demo data =='
        Invoke-Mysql ("source " + ($seed -replace '\\', '/')) | Out-Null
        $counts = (Invoke-Mysql "USE mall; SELECT CONCAT((SELECT COUNT(*) FROM t_category WHERE id IN (1,2,3,4,5) AND deleted=0),',',(SELECT COUNT(*) FROM t_product WHERE id IN (1,2,3,4,5) AND deleted=0),',',(SELECT COUNT(*) FROM t_sku WHERE id IN (1,2,3,4,5,6,7) AND deleted=0));").Trim()
        Check '演示数据已写入（分类/商品/SKU = 5/5/7）' ($counts -eq '5,5,7') "counts=$counts"
    }
}

# --------------------------------------------------------------------------- 3. 管理员账号引导
Write-Host '== admin bootstrap =='
$adminLogin = Http-Json -Method POST -Url "$admin/api/v1/admin/auth/login" -Body @{ username = $AdminUser; password = $AdminPass } -Name '01-admin-login'
if ((Code $adminLogin) -ne 200) {
    Invoke-Mysql "USE mall; DELETE FROM t_user WHERE username='$AdminUser';" | Out-Null
    Http-Json -Method POST -Url "$portal/api/v1/portal/auth/register" -Body @{ username = $AdminUser; password = $AdminPass; nickname = 'admin' } -Name '02-admin-register' | Out-Null
    Invoke-Mysql "USE mall; UPDATE t_user SET role=1, status=1, deleted=0 WHERE username='$AdminUser';" | Out-Null
    $adminLogin = Http-Json -Method POST -Url "$admin/api/v1/admin/auth/login" -Body @{ username = $AdminUser; password = $AdminPass } -Name '01-admin-login'
}
$adminToken = $null
if ((Code $adminLogin) -eq 200) { $adminToken = $adminLogin.body.data.token }
Check '管理员可登录后台' ((Code $adminLogin) -eq 200 -and $adminLogin.body.data.role -eq 1) "http=$($adminLogin.http) code=$(Code $adminLogin) role=$($adminLogin.body.data.role) token=$([bool]$adminToken)"

# --------------------------------------------------------------------------- 4. 前台：分类树 / 列表 / 搜索 / 筛选 / 排序
$a1 = Http-Json -Method GET -Url "$portal/api/v1/portal/category/tree" -Name '10-category-tree-anon'
$roots = @(Payload $a1)
$rootIds = @($roots | ForEach-Object { $_.id })
$cat1 = $null; foreach ($n in $roots) { if ($n.id -eq 1) { $cat1 = $n } }
Check '匿名取分类树（含顶级 1/4，数码下有子分类）' (
    $a1.http -eq 200 -and (Code $a1) -eq 200 -and ($rootIds -contains 1) -and ($rootIds -contains 4) -and
    $cat1 -and @($cat1.children).Count -ge 2
) "http=$($a1.http) code=$(Code $a1) roots=$($rootIds -join ',') children=$(@($cat1.children).Count)"

$a2 = Http-Json -Method GET -Url "$portal/api/v1/portal/product/page?pageSize=50" -Name '11-product-page-anon'
$list2 = Items $a2
$ids2 = @($list2 | ForEach-Object { $_.id })
Check '匿名商品列表只含上架商品（含 1/2/3/4，不含下架 id=5）' (
    (Code $a2) -eq 200 -and (Payload $a2).total -ge 4 -and ($ids2 -contains 1) -and ($ids2 -contains 4) -and $ids2 -notcontains 5
) "code=$(Code $a2) total=$((Payload $a2).total) ids=$($ids2 -join ',')"

$a3 = Http-Json -Method GET -Url "$portal/api/v1/portal/product/page?keyword=iPhone" -Name '12-product-search'
$list3 = Items $a3
Check '关键词搜索 name/sub_title（iPhone 命中 id=1）' (
    (Code $a3) -eq 200 -and (Payload $a3).total -ge 1 -and @($list3)[0].id -eq 1
) "code=$(Code $a3) total=$((Payload $a3).total)"

$a4 = Http-Json -Method GET -Url "$portal/api/v1/portal/product/page?categoryId=1&pageSize=50" -Name '13-product-by-parent-category'
$ids4 = @((Items $a4) | ForEach-Object { $_.id })
Check '按父分类聚合子分类商品（数码 -> 商品 1/3）' (
    (Code $a4) -eq 200 -and (Payload $a4).total -ge 3 -and ($ids4 -contains 1) -and ($ids4 -contains 3)
) "code=$(Code $a4) total=$((Payload $a4).total) ids=$($ids4 -join ',')"

$a5 = Http-Json -Method GET -Url "$portal/api/v1/portal/product/page?minPrice=5000&maxPrice=9000&pageSize=50" -Name '14-product-price-range'
$list5 = Items $a5
$inRange = $true; foreach ($p in $list5) { if ([decimal]$p.price -lt 5000 -or [decimal]$p.price -gt 9000) { $inRange = $false } }
$ids5 = @($list5 | ForEach-Object { $_.id })
Check '价格区间过滤（5000-9000 命中 7999 的 id=1）' (
    (Code $a5) -eq 200 -and $inRange -and ($ids5 -contains 1)
) "code=$(Code $a5) total=$((Payload $a5).total) ids=$($ids5 -join ',')"

$a6 = Http-Json -Method GET -Url "$portal/api/v1/portal/product/page?sort=price_asc&pageSize=50" -Name '15-product-sort-price-asc'
$prices = @((Items $a6) | ForEach-Object { [decimal]$_.price })
$asc = $true; for ($i = 1; $i -lt $prices.Count; $i++) { if ($prices[$i] -lt $prices[$i - 1]) { $asc = $false } }
Check '按价格升序排序' ((Code $a6) -eq 200 -and $asc -and $prices.Count -ge 4) "prices=$($prices -join ',')"

$a7 = Http-Json -Method GET -Url "$portal/api/v1/portal/product/1" -Name '16-product-detail'
$d7 = Payload $a7
Check '商品详情（含 2 个 SKU、图集数组、分类名）' (
    (Code $a7) -eq 200 -and @($d7.skus).Count -eq 2 -and @($d7.images).Count -ge 1 -and $d7.name -eq 'iPhone 15 Pro'
) "code=$(Code $a7) skus=$(@($d7.skus).Count) images=$(@($d7.images).Count)"

$a8 = Http-Json -Method GET -Url "$portal/api/v1/portal/product/999999" -Name '17-product-not-found'
Check '商品不存在 -> 业务码 2001' ((Code $a8) -eq 2001) "code=$(Code $a8)"

$a9 = Http-Json -Method GET -Url "$portal/api/v1/portal/product/5" -Name '18-product-off-shelf-detail'
Check '下架商品详情 -> 业务码 2002' ((Code $a9) -eq 2002) "code=$(Code $a9)"

# --------------------------------------------------------------------------- 5. 鉴权边界
$a10 = Http-Json -Method POST -Url "$admin/api/v1/admin/product" -Body @{ categoryId = 2; name = 'x'; price = 1; skus = @(@{ price = 1; stock = 1 }) } -Name '19-admin-product-anon'
Check '匿名访问后台商品接口 -> HTTP 401' ($a10.http -eq 401) "http=$($a10.http)"

$a11 = Http-Json -Method GET -Url "$portal/api/v1/portal/auth/login" -Name '20-method-not-allowed'
Check 'GET 打 POST 接口 -> HTTP 405 + code 405' ($a11.http -eq 405 -and (Code $a11) -eq 405) "http=$($a11.http) code=$(Code $a11)"

$a12 = Http-Json -Method GET -Url "$portal/api/v1/portal/auth/nope" -Name '21-not-found'
Check '路径不存在 -> HTTP 404 + code 404' ($a12.http -eq 404 -and (Code $a12) -eq 404) "http=$($a12.http) code=$(Code $a12)"

# --------------------------------------------------------------------------- 6. 后台：分类管理
$b1 = Http-Json -Method GET -Url "$admin/api/v1/admin/product/page?pageSize=50" -Token $adminToken -Name '30-admin-product-page'
Check '后台商品列表含下架（含 id=5）' ((Code $b1) -eq 200 -and (Payload $b1).total -ge 5 -and (@((Items $b1) | ForEach-Object { $_.id }) -contains 5)) "code=$(Code $b1) total=$((Payload $b1).total)"

$b2 = Http-Json -Method POST -Url "$admin/api/v1/admin/category" -Token $adminToken -Body @{ parentId = 0; name = 'P2 Test Cat'; sort = 99 } -Name '31-admin-category-create'
$newCatId = Payload $b2
Check '后台新增分类' ((Code $b2) -eq 200 -and $newCatId -gt 0) "code=$(Code $b2) id=$newCatId"

$b3 = Http-Json -Method PUT -Url "$admin/api/v1/admin/category/$newCatId" -Token $adminToken -Body @{ parentId = 0; name = 'P2 Test Cat Renamed'; sort = 98; status = 1 } -Name '32-admin-category-update'
$b4 = Http-Json -Method GET -Url "$admin/api/v1/admin/category/tree" -Token $adminToken -Name '33-admin-category-tree'
$found = $null; foreach ($n in @(Payload $b4)) { if ($n.id -eq $newCatId) { $found = $n } }
Check '后台改分类名生效' ((Code $b3) -eq 200 -and $found -and $found.name -eq 'P2 Test Cat Renamed') "code=$(Code $b3) name=$($found.name)"

$b5 = Http-Json -Method PUT -Url "$admin/api/v1/admin/category/$newCatId/status?status=0" -Token $adminToken -Name '34-admin-category-disable'
$b6 = Http-Json -Method GET -Url "$portal/api/v1/portal/category/tree" -Name '35-portal-tree-after-disable'
$stillThere = $false; foreach ($n in @(Payload $b6)) { if ($n.id -eq $newCatId) { $stillThere = $true } }
Check '禁用分类后前台分类树不再返回' ((Code $b5) -eq 200 -and -not $stillThere) "code=$(Code $b5) visible=$stillThere"
Http-Json -Method PUT -Url "$admin/api/v1/admin/category/$newCatId/status?status=1" -Token $adminToken -Name '36-admin-category-enable' | Out-Null

$b7 = Http-Json -Method DELETE -Url "$admin/api/v1/admin/category/1" -Token $adminToken -Name '37-admin-category-delete-has-data'
Check '删除含子分类/商品的分类被拒绝（2006）' ((Code $b7) -eq 2006) "code=$(Code $b7)"

$b8 = Http-Json -Method PUT -Url "$admin/api/v1/admin/category/1" -Token $adminToken -Body @{ parentId = 3; name = '数码'; sort = 1; status = 1 } -Name '38-admin-category-cycle'
Check '把分类挂到自己的子分类下被拒绝' ((Code $b8) -ne $null -and (Code $b8) -ne 200) "http=$($b8.http) code=$(Code $b8) msg=$($b8.body.message)"

$b9 = Http-Json -Method POST -Url "$admin/api/v1/admin/category" -Token $adminToken -Body @{ parentId = 0; name = 'P2 Test Cat Renamed' } -Name '39-admin-category-duplicate'
Check '同级同名分类被拒绝（2008）' ((Code $b9) -eq 2008) "code=$(Code $b9)"

# --------------------------------------------------------------------------- 7. 后台：商品 CRUD + SKU 同步 + 上下架
$c1 = Http-Json -Method POST -Url "$admin/api/v1/admin/product" -Token $adminToken -Body @{
    categoryId = $newCatId; name = 'P2 Test Phone'; subTitle = 'e2e'; price = 1999.00
    images = @('/img/t1.jpg', '/img/t2.jpg'); detail = '<p>e2e</p>'
    skus = @(
        @{ skuCode = 'P2-64';  spec = '64G';  price = 1999.00; stock = 10 },
        @{ skuCode = 'P2-128'; spec = '128G'; price = 2299.00; stock = 5 }
    )
} -Name '40-admin-product-create'
$newProductId = Payload $c1
Check '后台新增商品（SPU+2 SKU）' ((Code $c1) -eq 200 -and $newProductId -gt 0) "code=$(Code $c1) id=$newProductId"

$c2 = Http-Json -Method GET -Url "$admin/api/v1/admin/product/$newProductId" -Token $adminToken -Name '41-admin-product-detail'
$skus2 = @((Payload $c2).skus)
Check '新商品详情含 2 个 SKU' (@($skus2).Count -eq 2) "skus=$($skus2.Count)"
$keepSkuId = $null
foreach ($s in $skus2) { if ($s.spec -eq '64G') { $keepSkuId = $s.id } }

$c3 = Http-Json -Method PUT -Url "$admin/api/v1/admin/product/$newProductId" -Token $adminToken -Body @{
    categoryId = $newCatId; name = 'P2 Test Phone v2'; subTitle = 'e2e v2'; price = 1899.00
    images = @('/img/t1.jpg'); detail = '<p>e2e v2</p>'
    skus = @(
        @{ id = $keepSkuId; skuCode = 'P2-64'; spec = '64G'; price = 1899.00; stock = 8 },
        @{ skuCode = 'P2-256'; spec = '256G'; price = 2599.00; stock = 3 }
    )
} -Name '42-admin-product-update'
$c4 = Http-Json -Method GET -Url "$admin/api/v1/admin/product/$newProductId" -Token $adminToken -Name '43-admin-product-detail-after-update'
$skus4 = @((Payload $c4).skus)
$specs = @($skus4 | ForEach-Object { $_.spec })
Check 'SKU 同步（保留改价 + 新增 256G + 删除 128G）' (
    (Code $c3) -eq 200 -and $skus4.Count -eq 2 -and ($specs -contains '256G') -and ($specs -notcontains '128G')
) "specs=$($specs -join ',')"
Check '商品字段已更新（name/price/图集）' (
    (Payload $c4).name -eq 'P2 Test Phone v2' -and [decimal](Payload $c4).price -eq 1899.00 -and @((Payload $c4).images).Count -eq 1
) "name=$((Payload $c4).name) price=$((Payload $c4).price)"

$c5 = Http-Json -Method GET -Url "$portal/api/v1/portal/product/page?categoryId=$newCatId&pageSize=50" -Name '44-portal-list-new-product'
$np = @((Items $c5) | Where-Object { $_.id -eq $newProductId })
Check '前台列表能看到新商品，库存为 SKU 合计 11' (
    $np.Count -eq 1 -and $np[0].stock -eq 11 -and $np[0].categoryName -eq 'P2 Test Cat Renamed'
) "found=$($np.Count) stock=$($np[0].stock)"

$c6 = Http-Json -Method PUT -Url "$admin/api/v1/admin/product/$newProductId/status?status=0" -Token $adminToken -Name '45-admin-product-off'
$c7 = Http-Json -Method GET -Url "$portal/api/v1/portal/product/$newProductId" -Name '46-portal-detail-after-off'
Check '下架后前台详情 -> 2002' ((Code $c6) -eq 200 -and (Code $c7) -eq 2002) "code=$(Code $c7)"
$c8 = Http-Json -Method PUT -Url "$admin/api/v1/admin/product/$newProductId/status?status=1" -Token $adminToken -Name '47-admin-product-on'
$c9 = Http-Json -Method GET -Url "$portal/api/v1/portal/product/$newProductId" -Name '48-portal-detail-after-on'
Check '重新上架后前台详情可访问' ((Code $c8) -eq 200 -and (Code $c9) -eq 200) "code=$(Code $c9)"

# --------------------------------------------------------------------------- 8. 参数校验
$d1 = Http-Json -Method POST -Url "$admin/api/v1/admin/product" -Token $adminToken -Body @{ categoryId = $newCatId; price = 1; skus = @(@{ price = 1; stock = 1 }) } -Name '50-admin-product-missing-name'
Check '缺商品名 -> 校验失败 400' ((Code $d1) -eq 400) "code=$(Code $d1) msg=$($d1.body.message)"

$d2 = Http-Json -Method POST -Url "$admin/api/v1/admin/product" -Token $adminToken -Body @{ categoryId = 999999; name = 'bad cat'; price = 1; skus = @(@{ price = 1; stock = 1 }) } -Name '51-admin-product-bad-category'
Check '分类不存在 -> 2005' ((Code $d2) -eq 2005) "code=$(Code $d2)"

$d3 = Http-Json -Method POST -Url "$admin/api/v1/admin/product" -Token $adminToken -Body @{ categoryId = $newCatId; name = 'no sku'; price = 1; skus = @() } -Name '52-admin-product-no-sku'
Check '没有 SKU -> 校验失败 400' ((Code $d3) -eq 400) "code=$(Code $d3)"

# --------------------------------------------------------------------------- 9. 删除
$e1 = Http-Json -Method DELETE -Url "$admin/api/v1/admin/product/$newProductId" -Token $adminToken -Name '60-admin-product-delete'
$e2 = Http-Json -Method GET -Url "$portal/api/v1/portal/product/$newProductId" -Name '61-portal-detail-after-delete'
$e3 = Http-Json -Method GET -Url "$admin/api/v1/admin/product/page?keyword=P2%20Test&pageSize=50" -Token $adminToken -Name '62-admin-search-after-delete'
Check '删除商品后前台 2001、后台列表 0 条' (
    (Code $e1) -eq 200 -and (Code $e2) -eq 2001 -and (Payload $e3).total -eq 0
) "delete=$(Code $e1) portal=$(Code $e2) adminTotal=$((Payload $e3).total)"

$e4 = Http-Json -Method DELETE -Url "$admin/api/v1/admin/category/$newCatId" -Token $adminToken -Name '63-admin-category-delete'
Check '商品删完后分类可删' ((Code $e4) -eq 200) "code=$(Code $e4)"

# --------------------------------------------------------------------------- 汇总
$fail = @($script:results | Where-Object { -not $_.ok })
$lines = New-Object System.Collections.Generic.List[string]
$lines.Add("P2 验证汇总 " + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
$lines.Add("总计 $($script:results.Count) 项，通过 $($script:results.Count - $fail.Count) 项，失败 $($fail.Count) 项")
$lines.Add('')
foreach ($r in $script:results) {
    $tag = 'FAIL'; if ($r.ok) { $tag = 'PASS' }
    $lines.Add("[$tag] $($r.name) :: $($r.detail)")
}
$summary = ($lines -join "`r`n")
Write-Text (Join-Path $outDir 'summary.txt') $summary
Write-Host ''
Write-Host $summary

if ($fail.Count -gt 0) { exit 1 }
exit 0
