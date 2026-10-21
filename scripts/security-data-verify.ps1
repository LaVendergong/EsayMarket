# ============================================================================
# Sensitive-data security verification (passwords + PII).
#
# "Is our sensitive data encrypted?" is not a code-review question -- it is a set
# of assertions you can run. This script proves, against the real running system:
#
#   A) passwords: weak ones rejected, BCrypt at rest, never serialized to any API
#   B) PII at rest: phone / receiver / address columns hold ciphertext only,
#      and no plaintext phone number is left anywhere in those columns
#   C) round trip: writes land as ciphertext, reads come back correctly decrypted,
#      and the same phone number in two rows produces DIFFERENT ciphertext
#      (that is what proves it is not ECB -- see docs)
#   D) logs: no phone numbers, no password hashes, no tokens, no generated
#      default security password
#   E) metrics endpoint does not leak PII
#
# ASCII-only on purpose (see docs/AI-HANDOFF.md section 8, item 28).
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File D:\projects\mall\scripts\security-data-verify.ps1
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
$outDir = Join-Path $root 'tmp\security-data'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$reqFile = Join-Path $outDir '_request.json'
$script:results = New-Object System.Collections.Generic.List[object]
. (Join-Path $PSScriptRoot 'e2e-lib.ps1')

$CAT = 'SecData Cat'
$ITEM = 'SecData Item'
$SPEC = 'SECDATA-A'
$STAMP = (Get-Date -Format 'HHmmss')
$BUYER_A = "seca$STAMP"
$BUYER_B = "secb$STAMP"
$GOOD_PWD = 'seca123456'          # 8+ 位、含字母和数字
$PHONE_A = '13800001111'
$PHONE_B = '13800001111'          # 两个用户用同一个手机号，用来验证密文是否随机化
$RECEIVER = 'SecData Receiver'
$DETAIL = 'SecData Road 88'

function Sql([string]$q) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $mysql -uroot "-p$MysqlPassword" -N --default-character-set=utf8mb4 -e "USE mall; $q" 2>$null
        return ($out | Out-String).Trim()
    } finally { $ErrorActionPreference = $prev }
}

function Purge {
    Sql ("DELETE FROM t_order_item WHERE product_name = '$ITEM'; " +
        "DELETE o FROM t_order o WHERE o.user_id IN (SELECT id FROM t_user WHERE username IN ('$BUYER_A','$BUYER_B')); " +
        "DELETE FROM t_cart WHERE user_id IN (SELECT id FROM t_user WHERE username IN ('$BUYER_A','$BUYER_B')); " +
        "DELETE FROM t_address WHERE user_id IN (SELECT id FROM t_user WHERE username IN ('$BUYER_A','$BUYER_B')); " +
        "DELETE FROM t_user WHERE username IN ('$BUYER_A','$BUYER_B'); " +
        "DELETE s FROM t_sku s JOIN t_product p ON s.product_id = p.id WHERE p.name = '$ITEM'; " +
        "DELETE FROM t_product WHERE name = '$ITEM'; " +
        "DELETE FROM t_category WHERE name = '$CAT';") | Out-Null
}

# --------------------------------------------------------------------------- setup
Write-Host '== setup =='
Stop-MallApps | Out-Null
if (-not (Start-MallApps 'secdata')) {
    Check 'setup: both apps started' $false 'see logs'
    $fail = Save-Summary 'Sensitive data security verification'
    exit $fail
}
Check 'setup: both apps started' $true 'logs'

Purge
$adm = Http-Json -Method POST -Url "$admin/api/v1/admin/auth/login" -Body @{ username = 'malladmin'; password = 'admin123456' } -Name '01-admin'
$adminToken = (Payload $adm).token
$cat = Http-Json -Method POST -Url "$admin/api/v1/admin/category" -Token $adminToken -Body @{ parentId = 0; name = $CAT; sort = 94 } -Name '02-cat'
$prod = Http-Json -Method POST -Url "$admin/api/v1/admin/product" -Token $adminToken -Body @{
    categoryId = (Payload $cat); name = $ITEM; subTitle = 'secdata'; price = 10
    skus = @( @{ skuCode = $SPEC; spec = $SPEC; price = 10; stock = 50 } )
} -Name '03-product'
$prodId = [long](Payload $prod)
$skuId = [long](Sql "SELECT id FROM t_sku WHERE product_id = $prodId LIMIT 1;")
Check 'setup: test product ready' ($prodId -gt 0 -and $skuId -gt 0) "productId=$prodId skuId=$skuId"

# ============================================================ A) password strength
Write-Host ''
Write-Host '== A) password policy =='
$weak1 = Http-Json -Method POST -Url "$portal/api/v1/portal/auth/register" -Body @{ username = "weak1$STAMP"; password = '123456' } -Name '10-weak6'
Check 'A1 6-digit all-numeric password rejected' ((Code $weak1) -eq 400) "code=$(Code $weak1) msg=$($weak1.body.message)"
$weak2 = Http-Json -Method POST -Url "$portal/api/v1/portal/auth/register" -Body @{ username = "weak2$STAMP"; password = 'abcdefgh' } -Name '11-weakalpha'
Check 'A2 8-letter no-digit password rejected' ((Code $weak2) -eq 400) "code=$(Code $weak2) msg=$($weak2.body.message)"
$weak3 = Http-Json -Method POST -Url "$portal/api/v1/portal/auth/register" -Body @{ username = "weak3$STAMP"; password = 'a1' } -Name '12-short'
Check 'A3 too-short password rejected' ((Code $weak3) -eq 400) "code=$(Code $weak3) msg=$($weak3.body.message)"
$weak4 = Http-Json -Method POST -Url "$portal/api/v1/portal/auth/register" -Body @{ username = "weak4$STAMP"; password = ('a1' + ('密' * 30)) } -Name '13-over72bytes'
Check 'A4 password over the 72-byte BCrypt limit rejected' ((Code $weak4) -eq 400) "code=$(Code $weak4) msg=$($weak4.body.message)"

$regA = Http-Json -Method POST -Url "$portal/api/v1/portal/auth/register" -Body @{ username = $BUYER_A; password = $GOOD_PWD; nickname = 'A'; phone = $PHONE_A } -Name '14-regA'
Check 'A5 strong password accepted' ((Code $regA) -eq 200) "code=$(Code $regA) username=$BUYER_A"

$hashA = Sql "SELECT password FROM t_user WHERE username = '$BUYER_A';"
Check 'A6 password stored as BCrypt hash' ($hashA -match '^\$2[aby]\$\d{2}\$') "prefix=$($hashA.Substring(0, [Math]::Min(7, $hashA.Length)))"
Check 'A7 stored hash is not the plaintext' ($hashA -ne $GOOD_PWD -and $hashA.Length -ge 50) "len=$($hashA.Length)"

$loginA = Http-Json -Method POST -Url "$portal/api/v1/portal/auth/login" -Body @{ username = $BUYER_A; password = $GOOD_PWD } -Name '15-loginA'
$tokenA = (Payload $loginA).token
$userJson = $loginA.raw
Check 'A8 login works with the strong password' ((Code $loginA) -eq 200 -and $tokenA.Length -gt 20) "code=$(Code $loginA)"

# 任何响应里都不该出现密码哈希（这里拿登录响应 + 用户信息接口一起验）
$meResp = Http-Json -Method GET -Url "$portal/api/v1/portal/user/me" -Token $tokenA -Name '16-me'
Check 'A9 current-user endpoint reachable' ((Code $meResp) -eq 200) "code=$(Code $meResp)"
$meRaw = $meResp.raw
$leak = ($meRaw -match 'password') -or ($meRaw -match '\$2[aby]\$') -or ($userJson -match 'password') -or ($userJson -match '\$2[aby]\$')
Check 'A9 user info response carries no password field/hash' (-not $leak) "code=$(Code $meResp)"

# ============================================================ B) PII at rest
Write-Host ''
Write-Host '== B) PII encrypted at rest (columns hold ciphertext only) =='
$addrPlain = [int](Sql "SELECT COUNT(*) FROM t_address WHERE phone NOT LIKE 'enc:v1:%' OR receiver NOT LIKE 'enc:v1:%' OR detail NOT LIKE 'enc:v1:%';")
$addrTotal = [int](Sql "SELECT COUNT(*) FROM t_address;")
Check 'B1 every t_address PII value is ciphertext' ($addrPlain -eq 0) "rows=$addrTotal plaintextValues=$addrPlain"

$orderPlain = [int](Sql "SELECT COUNT(*) FROM t_order WHERE (receiver IS NOT NULL AND receiver NOT LIKE 'enc:v1:%') OR (receiver_phone IS NOT NULL AND receiver_phone NOT LIKE 'enc:v1:%') OR (receiver_addr IS NOT NULL AND receiver_addr NOT LIKE 'enc:v1:%');")
$orderTotal = [int](Sql "SELECT COUNT(*) FROM t_order;")
Check 'B2 every t_order PII value is ciphertext' ($orderPlain -eq 0) "rows=$orderTotal plaintextValues=$orderPlain"

$userPlain = [int](Sql "SELECT COUNT(*) FROM t_user WHERE phone IS NOT NULL AND phone <> '' AND phone NOT LIKE 'enc:v1:%';")
Check 'B3 no plaintext phone left in t_user' ($userPlain -eq 0) "plaintextValues=$userPlain"

$phoneHit = [int](Sql "SELECT COUNT(*) FROM t_address WHERE phone LIKE '%13800138000%' OR receiver LIKE '%Zhang San%';")
Check 'B4 known seed PII is no longer readable from columns' ($phoneHit -eq 0) "hits=$phoneHit"

# ============================================================ C) write/read round trip
Write-Host ''
Write-Host '== C) round trip: ciphertext at rest, plaintext through the API =='
$addrResp = Http-Json -Method POST -Url "$portal/api/v1/portal/address" -Token $tokenA -Body @{
    receiver = $RECEIVER; phone = $PHONE_A; province = 'GD'; city = 'SZ'; district = 'NS'
    detail = $DETAIL; isDefault = 1
} -Name '20-addrA'
$addrIdA = [long](Payload $addrResp)
Check 'C1 address created' ($addrIdA -gt 0) "addressId=$addrIdA"

$stored = Sql "SELECT CONCAT(LEFT(receiver,7),'|',LEFT(phone,7),'|',LEFT(detail,7)) FROM t_address WHERE id = $addrIdA;"
Check 'C2 stored value is ciphertext (enc:v1: prefix)' ($stored -eq 'enc:v1:|enc:v1:|enc:v1:') "stored=$stored"

$listResp = Http-Json -Method GET -Url "$portal/api/v1/portal/address" -Token $tokenA -Name '21-list-addr'
$listJson = $listResp.raw
Check 'C3 API returns decrypted plaintext' ($listJson -match [regex]::Escape($PHONE_A) -and $listJson -match [regex]::Escape($RECEIVER)) "phoneVisible=$($listJson -match [regex]::Escape($PHONE_A))"
Check 'C4 API response does not expose ciphertext' ($listJson -notmatch 'enc:v1:') "containsCiphertext=$($listJson -match 'enc:v1:')"

# 下单：订单快照也要是密文，但详情接口要能解开
Http-Json -Method POST -Url "$portal/api/v1/portal/cart" -Token $tokenA -Body @{ skuId = $skuId; quantity = 1 } -Name '22-cart' | Out-Null
$orderResp = Http-Json -Method POST -Url "$portal/api/v1/portal/order" -Token $tokenA -Body @{ addressId = $addrIdA; requestId = "secdata-$STAMP" } -Name '23-order'
$orderId = [long](Payload $orderResp).orderId
Check 'C5 order created' ($orderId -gt 0) "orderId=$orderId code=$(Code $orderResp)"

$orderStored = Sql "SELECT CONCAT(LEFT(IFNULL(receiver,'-'),7),'|',LEFT(IFNULL(receiver_phone,'-'),7),'|',LEFT(IFNULL(receiver_addr,'-'),7)) FROM t_order WHERE id = $orderId;"
Check 'C6 order snapshot stored as ciphertext' ($orderStored -eq 'enc:v1:|enc:v1:|enc:v1:') "stored=$orderStored"

$orderDetail = Http-Json -Method GET -Url "$portal/api/v1/portal/order/$orderId" -Token $tokenA -Name '24-order-detail'
$orderJson = $orderDetail.raw
Check 'C7 order detail decrypts receiver/phone/address' `
    ($orderJson -match [regex]::Escape($RECEIVER) -and $orderJson -match [regex]::Escape($PHONE_A) -and $orderJson -match [regex]::Escape($DETAIL)) `
    "receiver=$($orderJson -match [regex]::Escape($RECEIVER)) phone=$($orderJson -match [regex]::Escape($PHONE_A)) addr=$($orderJson -match [regex]::Escape($DETAIL))"

# 同一个手机号落在两行 -> 密文必须不同（随机 IV；ECB 会相同）
$regB = Http-Json -Method POST -Url "$portal/api/v1/portal/auth/register" -Body @{ username = $BUYER_B; password = $GOOD_PWD; nickname = 'B'; phone = $PHONE_B } -Name '25-regB'
$cipherA = Sql "SELECT phone FROM t_user WHERE username = '$BUYER_A';"
$cipherB = Sql "SELECT phone FROM t_user WHERE username = '$BUYER_B';"
Check 'C8 same phone in two rows yields different ciphertext (random IV, not ECB)' `
    ($cipherA -ne $cipherB -and $cipherA.Length -gt 20 -and $cipherB.Length -gt 20) `
    "lenA=$($cipherA.Length) lenB=$($cipherB.Length) same=$($cipherA -eq $cipherB)"

# ============================================================ D) logs
Write-Host ''
Write-Host '== D) logs carry no sensitive data =='
$logFiles = @((Join-Path $root 'logs\admin.log'), (Join-Path $root 'logs\portal.log'))
$phoneLines = 0
$pwdLines = 0
$tokenLines = 0
$genPwdLines = 0
foreach ($f in $logFiles) {
    if (-not (Test-Path $f)) { continue }
    $phoneLines += (Select-String -Path $f -Pattern '(?<![\d.])1[3-9]\d{9}(?![\d.])' -AllMatches -ErrorAction SilentlyContinue | Measure-Object).Count
    $pwdLines += (Select-String -Path $f -Pattern '\$2[aby]\$\d{2}\$' -ErrorAction SilentlyContinue | Measure-Object).Count
    $tokenLines += (Select-String -Path $f -Pattern 'eyJ[A-Za-z0-9_-]{10,}|Bearer ' -ErrorAction SilentlyContinue | Measure-Object).Count
    $genPwdLines += (Select-String -Path $f -Pattern 'Using generated security password' -ErrorAction SilentlyContinue | Measure-Object).Count
}
Check 'D1 no plaintext phone number in logs' ($phoneLines -eq 0) "matches=$phoneLines"
Check 'D2 no BCrypt hash in logs' ($pwdLines -eq 0) "matches=$pwdLines"
Check 'D3 no JWT/Bearer token in logs' ($tokenLines -eq 0) "matches=$tokenLines"
Check 'D4 no spring generated default password in logs' ($genPwdLines -eq 0) "matches=$genPwdLines"
# 只认「前缀 + 真密文载荷」：启动日志里那句「密文前缀 enc:v1:」是常量说明，不是数据
Check 'D5 no ciphertext dumped into logs' ((Select-String -Path $logFiles -Pattern 'enc:v1:[A-Za-z0-9_-]{16,}' -ErrorAction SilentlyContinue | Measure-Object).Count -eq 0) 'enc:v1:<payload>'

# ============================================================ E) metrics
Write-Host ''
Write-Host '== E) metrics endpoint =='
$metricsBody = ''
$prevEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try { $metricsBody = (curl.exe -s -m 10 "$portal/actuator/prometheus" 2>$null | Out-String) } finally { $ErrorActionPreference = $prevEap }
Check 'E1 prometheus reachable' ($metricsBody.Length -gt 500) "bytes=$($metricsBody.Length)"
Check 'E2 prometheus leaks no phone / no ciphertext' ($metricsBody -notmatch '(?<![\d.])1[3-9]\d{9}(?![\d.])' -and $metricsBody -notmatch 'enc:v1:[A-Za-z0-9_-]{16,}') "phone=$($metricsBody -match '(?<![\d.])1[3-9]\d{9}(?![\d.])') enc=$($metricsBody -match 'enc:v1:[A-Za-z0-9_-]{16,}')"

# ============================================================ cleanup
Write-Host ''
Write-Host '== cleanup =='
Purge
Check 'cleanup: test data removed' ([int](Sql "SELECT COUNT(*) FROM t_user WHERE username IN ('$BUYER_A','$BUYER_B');") -eq 0) 'leftover users = 0'

$fail = Save-Summary 'Sensitive data security verification (password + PII)'
exit $fail
